package com.arshad.ayn.core.lock

/** [AwayRecords] in memory; [record] is what's on "disk". */
class FakeAwayRecords(var record: AwayRecord? = null) : AwayRecords {
    override suspend fun read(): AwayRecord? = record

    override suspend fun write(record: AwayRecord?) {
        this.record = record
    }
}
