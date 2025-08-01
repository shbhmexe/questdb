/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.join;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.ColumnTypes;
import io.questdb.cairo.RecordSink;
import io.questdb.cairo.map.Map;
import io.questdb.cairo.map.MapFactory;
import io.questdb.cairo.map.MapKey;
import io.questdb.cairo.map.MapValue;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.cairo.sql.SqlExecutionCircuitBreaker;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.model.JoinContext;
import io.questdb.std.Misc;
import io.questdb.std.Numbers;
import io.questdb.std.Transient;

public class AsOfJoinLightRecordCursorFactory extends AbstractJoinRecordCursorFactory {
    private final AsOfLightJoinRecordCursor cursor;
    private final RecordSink masterKeySink;
    private final RecordSink slaveKeySink;
    private final long toleranceInterval;

    public AsOfJoinLightRecordCursorFactory(
            CairoConfiguration configuration,
            RecordMetadata metadata,
            RecordCursorFactory masterFactory,
            RecordCursorFactory slaveFactory,
            @Transient ColumnTypes joinColumnTypes,
            @Transient ColumnTypes valueTypes, // this expected to be just 2 LONGs, we store chain references in map
            RecordSink masterKeySink,
            RecordSink slaveKeySink,
            int columnSplit,
            JoinContext joinContext,
            long toleranceInterval
    ) {
        super(metadata, joinContext, masterFactory, slaveFactory);
        try {
            this.masterKeySink = masterKeySink;
            this.slaveKeySink = slaveKeySink;
            this.toleranceInterval = toleranceInterval;

            Map joinKeyMap = MapFactory.createUnorderedMap(configuration, joinColumnTypes, valueTypes);
            this.cursor = new AsOfLightJoinRecordCursor(
                    columnSplit,
                    joinKeyMap,
                    NullRecordFactory.getInstance(slaveFactory.getMetadata()),
                    masterFactory.getMetadata().getTimestampIndex(),
                    slaveFactory.getMetadata().getTimestampIndex()
            );
        } catch (Throwable th) {
            close();
            throw th;
        }
    }

    @Override
    public boolean followedOrderByAdvice() {
        return masterFactory.followedOrderByAdvice();
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) throws SqlException {
        // Forcefully disable column pre-touch for nested filter queries.
        executionContext.setColumnPreTouchEnabled(false);
        RecordCursor masterCursor = masterFactory.getCursor(executionContext);
        RecordCursor slaveCursor = null;
        try {
            slaveCursor = slaveFactory.getCursor(executionContext);
            cursor.of(masterCursor, slaveCursor);
        } catch (Throwable ex) {
            Misc.free(masterCursor);
            Misc.free(slaveCursor);
            throw ex;
        }
        return cursor;
    }

    @Override
    public int getScanDirection() {
        return masterFactory.getScanDirection();
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return false;
    }

    @Override
    public void toPlan(PlanSink sink) {
        sink.type("AsOf Join Light");
        sink.attr("condition").val(joinContext);
        sink.child(masterFactory);
        sink.child(slaveFactory);
    }

    @Override
    protected void _close() {
        Misc.freeIfCloseable(getMetadata());
        Misc.free(masterFactory);
        Misc.free(slaveFactory);
        Misc.free(cursor);
    }

    private class AsOfLightJoinRecordCursor extends AbstractJoinCursor {
        private final Map joinKeyMap;
        private final int masterTimestampIndex;
        private final OuterJoinRecord record;
        private final int slaveTimestampIndex;
        private boolean isMasterHasNextPending;
        private boolean isOpen;
        private long lastSlaveRowID = Long.MIN_VALUE;
        private boolean masterHasNext;
        private Record masterRecord;
        private Record slaveRecord;
        private long slaveTimestamp = Long.MIN_VALUE;

        public AsOfLightJoinRecordCursor(
                int columnSplit,
                Map joinKeyMap,
                Record nullRecord,
                int masterTimestampIndex,
                int slaveTimestampIndex
        ) {
            super(columnSplit);
            record = new OuterJoinRecord(columnSplit, nullRecord);
            this.joinKeyMap = joinKeyMap;
            this.masterTimestampIndex = masterTimestampIndex;
            this.slaveTimestampIndex = slaveTimestampIndex;
            isOpen = true;
        }

        @Override
        public void calculateSize(SqlExecutionCircuitBreaker circuitBreaker, Counter counter) {
            masterCursor.calculateSize(circuitBreaker, counter);
        }

        @Override
        public void close() {
            if (isOpen) {
                isOpen = false;
                joinKeyMap.close();
                super.close();
            }
        }

        @Override
        public Record getRecord() {
            return record;
        }

        @Override
        public boolean hasNext() {
            if (isMasterHasNextPending) {
                masterHasNext = masterCursor.hasNext();
                isMasterHasNextPending = false;
            }
            if (masterHasNext) {
                final long masterTimestamp = masterRecord.getTimestamp(masterTimestampIndex);
                final long minSlaveTimestamp = toleranceInterval == Numbers.LONG_NULL ? Long.MIN_VALUE : masterTimestamp - toleranceInterval;
                MapKey key;
                MapValue value;
                long slaveTimestamp = this.slaveTimestamp;
                long slaveRowID = this.lastSlaveRowID;
                if (slaveTimestamp <= masterTimestamp) {
                    if (lastSlaveRowID != Numbers.LONG_NULL) {
                        slaveCursor.recordAt(slaveRecord, lastSlaveRowID);
                        slaveTimestamp = slaveRecord.getTimestamp(slaveTimestampIndex);
                        if (slaveTimestamp >= minSlaveTimestamp) {
                            key = joinKeyMap.withKey();
                            key.put(slaveRecord, slaveKeySink);
                            value = key.createValue();
                            value.putLong(0, lastSlaveRowID);
                        }
                    }

                    // NOTE: unlike full fat ASOF JOIN, we don't evacuate joinKeyMap here.
                    // Reasoning: The joinKeyMap here contains only rowNo, so evacuation
                    // would require to dereference rowNo to record to get a timestamp
                    // to decide whether to keep the record or not. This could be expensive.
                    // Given map values are just row IDs, I decided not to evacuate
                    // maps in Light ASOF JOINs

                    final Record rec = slaveCursor.getRecord();
                    while (slaveCursor.hasNext()) {
                        slaveTimestamp = rec.getTimestamp(slaveTimestampIndex);
                        slaveRowID = rec.getRowId();
                        if (slaveTimestamp <= masterTimestamp) {
                            if (slaveTimestamp >= minSlaveTimestamp) {
                                key = joinKeyMap.withKey();
                                key.put(rec, slaveKeySink);
                                value = key.createValue();
                                value.putLong(0, rec.getRowId());
                            }
                        } else {
                            break;
                        }
                    }

                    // now we have dangling slave record, which we need to hold on to
                    this.slaveTimestamp = slaveTimestamp;
                    this.lastSlaveRowID = slaveRowID;
                }
                key = joinKeyMap.withKey();
                key.put(masterRecord, masterKeySink);
                value = key.findValue();
                if (value != null) {
                    slaveCursor.recordAt(slaveRecord, value.getLong(0));
                    slaveTimestamp = slaveRecord.getTimestamp(slaveTimestampIndex);
                    record.hasSlave(toleranceInterval == Numbers.LONG_NULL || slaveTimestamp >= masterTimestamp - toleranceInterval);
                } else {
                    record.hasSlave(false);
                }

                isMasterHasNextPending = true;
                return true;
            }
            return false;
        }

        @Override
        public long preComputedStateSize() {
            return masterCursor.preComputedStateSize() + slaveCursor.preComputedStateSize();
        }

        @Override
        public long size() {
            return masterCursor.size();
        }

        @Override
        public void toTop() {
            joinKeyMap.clear();
            slaveTimestamp = Long.MIN_VALUE;
            lastSlaveRowID = Long.MIN_VALUE;
            masterCursor.toTop();
            slaveCursor.toTop();
            isMasterHasNextPending = true;
        }

        void of(RecordCursor masterCursor, RecordCursor slaveCursor) {
            if (!isOpen) {
                isOpen = true;
                joinKeyMap.reopen();
            }
            slaveTimestamp = Long.MIN_VALUE;
            lastSlaveRowID = Long.MIN_VALUE;
            this.masterCursor = masterCursor;
            this.slaveCursor = slaveCursor;
            masterRecord = masterCursor.getRecord();
            slaveRecord = slaveCursor.getRecordB();
            record.of(masterRecord, slaveRecord);
            isMasterHasNextPending = true;
        }
    }
}
