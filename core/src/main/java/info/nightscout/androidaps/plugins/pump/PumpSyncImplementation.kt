package info.nightscout.androidaps.plugins.pump

import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.ValueWrapper
import info.nightscout.androidaps.database.embedments.InterfaceIDs
import info.nightscout.androidaps.database.entities.Bolus
import info.nightscout.androidaps.database.entities.Carbs
import info.nightscout.androidaps.database.entities.ExtendedBolus
import info.nightscout.androidaps.database.entities.TemporaryBasal
import info.nightscout.androidaps.database.entities.TherapyEvent
import info.nightscout.androidaps.database.transactions.*
import info.nightscout.androidaps.interfaces.ProfileFunction
import info.nightscout.androidaps.interfaces.PumpSync
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.pump.common.defs.PumpType
import info.nightscout.androidaps.utils.DateUtil
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import javax.inject.Inject

class PumpSyncImplementation @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val profileFunction: ProfileFunction,
    private val repository: AppRepository
) : PumpSync {

    private val disposable = CompositeDisposable()

    override fun expectedPumpState(): PumpSync.PumpState {
        val bolus = repository.getLastBolusRecord()
        val temporaryBasal = repository.getTemporaryBasalActiveAt(dateUtil._now()).blockingGet()
        val extendedBolus = repository.getExtendedBolusActiveAt(dateUtil._now()).blockingGet()

        return PumpSync.PumpState(
            temporaryBasal =
            if (temporaryBasal is ValueWrapper.Existing)
                PumpSync.PumpState.TemporaryBasal(
                    id = temporaryBasal.value.id,
                    timestamp = temporaryBasal.value.timestamp,
                    duration = temporaryBasal.value.duration,
                    rate = temporaryBasal.value.rate,
                    isAbsolute = temporaryBasal.value.isAbsolute,
                    type = PumpSync.TemporaryBasalType.fromDbType(temporaryBasal.value.type),
                    pumpId = temporaryBasal.value.interfaceIDs.pumpId
                )
            else null,
            extendedBolus =
            if (extendedBolus is ValueWrapper.Existing)
                PumpSync.PumpState.ExtendedBolus(
                    timestamp = extendedBolus.value.timestamp,
                    duration = extendedBolus.value.duration,
                    amount = extendedBolus.value.amount,
                    rate = extendedBolus.value.rate
                )
            else null,
            bolus =
            bolus?.let {
                PumpSync.PumpState.Bolus(
                    timestamp = bolus.timestamp,
                    amount = bolus.amount
                )
            },
            profile = profileFunction.getProfile()
        )
    }

    override fun addBolusWithTempId(timestamp: Long, amount: Double, temporaryId: Long, type: DetailedBolusInfo.BolusType, pumpType: PumpType, pumpSerial: String): Boolean {
        val bolus = Bolus(
            timestamp = timestamp,
            amount = amount,
            type = type.toDBbBolusType(),
            interfaceIDs_backing = InterfaceIDs(
                temporaryId = temporaryId,
                pumpType = pumpType.toDbPumpType(),
                pumpSerial = pumpSerial
            )
        )
        repository.runTransactionForResult(InsertPumpBolusWithTempIdTransaction(bolus))
            .doOnError { aapsLogger.error(LTag.DATABASE, "Error while saving bolus", it) }
            .blockingGet()
            .also { result ->
                result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted bolus $it") }
                return result.inserted.size > 0
            }
    }

    override fun syncBolusWithTempId(timestamp: Long, amount: Double, temporaryId: Long, type: DetailedBolusInfo.BolusType?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        val bolus = Bolus(
            timestamp = timestamp,
            amount = amount,
            type = Bolus.Type.NORMAL, // not used for update
            interfaceIDs_backing = InterfaceIDs(
                temporaryId = temporaryId,
                pumpId = pumpId,
                pumpType = pumpType.toDbPumpType(),
                pumpSerial = pumpSerial
            )
        )
        repository.runTransactionForResult(SyncPumpBolusWithTempIdTransaction(bolus, type?.toDBbBolusType()))
            .doOnError { aapsLogger.error(LTag.DATABASE, "Error while saving bolus", it) }
            .blockingGet()
            .also { result ->
                result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated bolus $it") }
                return result.updated.size > 0
            }
    }

    override fun syncBolusWithPumpId(timestamp: Long, amount: Double, type: DetailedBolusInfo.BolusType?, pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        val bolus = Bolus(
            timestamp = timestamp,
            amount = amount,
            type = type?.toDBbBolusType() ?: Bolus.Type.NORMAL,
            interfaceIDs_backing = InterfaceIDs(
                pumpId = pumpId,
                pumpType = pumpType.toDbPumpType(),
                pumpSerial = pumpSerial
            )
        )
        repository.runTransactionForResult(SyncPumpBolusTransaction(bolus, type?.toDBbBolusType()))
            .doOnError { aapsLogger.error(LTag.DATABASE, "Error while saving bolus", it) }
            .blockingGet()
            .also { result ->
                result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted bolus $it") }
                result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated bolus $it") }
                return result.inserted.size > 0
            }
    }

    override fun syncCarbsWithTimestamp(timestamp: Long, amount: Double, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        val carbs = Carbs(
            timestamp = timestamp,
            amount = amount,
            duration = 0,
            interfaceIDs_backing = InterfaceIDs(
                pumpId = pumpId,
                pumpType = pumpType.toDbPumpType(),
                pumpSerial = pumpSerial)
        )
        repository.runTransactionForResult(InsertIfNewByTimestampCarbsTransaction(carbs))
            .doOnError { aapsLogger.error(LTag.DATABASE, "Error while saving carbs", it) }
            .blockingGet()
            .also { result ->
                result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted carbs $it") }
                return result.inserted.size > 0
            }
    }

    override fun insertTherapyEventIfNewWithTimestamp(timestamp: Long, type: DetailedBolusInfo.EventType, note: String?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        val therapyEvent = TherapyEvent(
            timestamp = timestamp,
            type = type.toDBbEventType(),
            duration = 0,
            note = null,
            enteredBy = "AndroidAPS",
            glucose = null,
            glucoseType = null,
            glucoseUnit = TherapyEvent.GlucoseUnit.MGDL,
            interfaceIDs_backing = InterfaceIDs(
                pumpId = pumpId,
                pumpType = pumpType.toDbPumpType(),
                pumpSerial = pumpSerial)
        )
        repository.runTransactionForResult(InsertIfNewByTimestampTherapyEventTransaction(therapyEvent))
            .doOnError {
                aapsLogger.error(LTag.DATABASE, "Error while saving therapy event", it)
            }
            .blockingGet()
            .also { result ->
                result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted therapy event $it") }
                return result.inserted.size > 0
            }
    }

    override fun insertAnnouncement(error: String, pumpId: Long?, pumpType: PumpType, pumpSerial: String) {
        disposable += repository.runTransaction(InsertTherapyEventAnnouncementTransaction(error, pumpId, pumpType.toDbPumpType(), pumpSerial))
            .subscribe()
    }

    /*
     *   TEMPORARY BASALS
     */

    override fun syncTemporaryBasalWithPumpId(timestamp: Long, rate: Double, duration: Long, isAbsolute: Boolean, type: PumpSync.TemporaryBasalType?, pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        val temporaryBasal = TemporaryBasal(
            timestamp = timestamp,
            rate = rate,
            duration = duration,
            type = type?.toDbType() ?: TemporaryBasal.Type.NORMAL,
            isAbsolute = isAbsolute,
            interfaceIDs_backing = InterfaceIDs(
                pumpId = pumpId,
                pumpType = pumpType.toDbPumpType(),
                pumpSerial = pumpSerial
            )
        )
        repository.runTransactionForResult(SyncPumpTemporaryBasalTransaction(temporaryBasal, type?.toDbType()))
            .doOnError { aapsLogger.error(LTag.DATABASE, "Error while temporary basal", it) }
            .blockingGet()
            .also { result ->
                result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted temporary basal $it") }
                result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated temporary basal $it") }
                return result.inserted.size > 0
            }
    }

    override fun syncStopTemporaryBasalWithPumpId(timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        repository.runTransactionForResult(SyncPumpCancelTemporaryBasalIfAnyTransaction(timestamp, endPumpId, pumpType.toDbPumpType(), pumpSerial))
            .doOnError { aapsLogger.error(LTag.DATABASE, "Error while saving temporary basal", it) }
            .blockingGet()
            .also { result ->
                result.updated.forEach {
                    aapsLogger.debug(LTag.DATABASE, "Updated temporary basal $it")
                }
                return result.updated.size > 0
            }
    }

    override fun invalidateTemporaryBasal(id: Long): Boolean {
        repository.runTransactionForResult(InvalidateTemporaryBasalTransaction(id))
            .doOnError { aapsLogger.error(LTag.DATABASE, "Error while invalidating temporary basal", it) }
            .blockingGet()
            .also { result ->
                result.invalidated.forEach {
                    aapsLogger.debug(LTag.DATABASE, "Invalidated temporary basal $it")
                }
                return result.invalidated.size > 0
            }
    }

    override fun syncExtendedBolusWithPumpId(timestamp: Long, amount: Double, duration: Long, isEmulatingTB: Boolean, pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        val extendedBolus = ExtendedBolus(
            timestamp = timestamp,
            amount = amount,
            duration = duration,
            isEmulatingTempBasal = isEmulatingTB,
            interfaceIDs_backing = InterfaceIDs(
                pumpId = pumpId,
                pumpType = pumpType.toDbPumpType(),
                pumpSerial = pumpSerial
            )
        )
        repository.runTransactionForResult(SyncPumpExtendedBolusTransaction(extendedBolus))
            .doOnError { aapsLogger.error(LTag.DATABASE, "Error while extended bolus", it) }
            .blockingGet()
            .also { result ->
                result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted extended bolus $it") }
                result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated extended bolus $it") }
                return result.inserted.size > 0
            }
    }

    override fun syncStopExtendedBolusWithPumpId(timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        repository.runTransactionForResult(SyncPumpCancelExtendedBolusIfAnyTransaction(timestamp, endPumpId, pumpType.toDbPumpType(), pumpSerial))
            .doOnError { aapsLogger.error(LTag.DATABASE, "Error while saving extended bolus", it) }
            .blockingGet()
            .also { result ->
                result.updated.forEach {
                    aapsLogger.debug(LTag.DATABASE, "Updated extended bolus $it")
                }
                return result.updated.size > 0
            }
    }

}