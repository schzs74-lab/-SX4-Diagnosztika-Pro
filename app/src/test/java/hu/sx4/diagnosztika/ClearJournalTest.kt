package hu.sx4.diagnosztika

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ClearJournalTest {
    private fun prefs()=RuntimeEnvironment.getApplication().getSharedPreferences("clear-journal-test",0)
    @Test fun unknownAttemptSurvivesRestartAndCannotBeOverwritten() {
        val p=prefs();val journal=ClearJournal(p);journal.dispatch("7E8",6,"connection-A")
        assertTrue(ClearJournal(p).warning().contains("7E8"));assertTrue(p.getBoolean("clearPending",false))
        assertThrows(IllegalStateException::class.java){journal.dispatch("7E9",6,"connection-B")}
        assertTrue(journal.warning().contains("7E8"))
    }
    @Test fun onlyMatchingConclusiveAttemptCanResolvePendingState() {
        val p=prefs();val journal=ClearJournal(p);val id=journal.dispatch("7E8",6,"connection-A")
        journal.finish("other","ACKNOWLEDGED");assertTrue(p.getBoolean("clearPending",false))
        journal.finish(id,"REJECTED_22");assertFalse(p.getBoolean("clearPending",false))
        assertTrue(p.getString("clearEntry","")!!.contains("REJECTED_22"))
    }
    @Test fun userReviewRetainsUnknownOutcomeAndArchivesOldSource() {
        val p=prefs();val journal=ClearJournal(p);journal.dispatch("7E8",6,"connection-A")
        journal.acknowledgeUnknown();assertFalse(p.getBoolean("clearPending",false))
        assertTrue(p.getString("clearEntry","")!!.contains("USER_REVIEWED_UNKNOWN"))
        journal.dispatch("7E9",6,"connection-B")
        assertTrue(p.getString("clearHistory","")!!.contains("7E8"))
        assertTrue(journal.warning().contains("7E9"))
    }
    @Test fun oldUnscopedWarningRemainsReviewableWithoutClaimingSuccess() {
        val p=prefs();p.edit().putBoolean("clearPending",true).commit()
        val journal=ClearJournal(p);assertTrue(journal.warning().contains("BIZONYTALAN"))
        journal.acknowledgeUnknown();assertTrue(p.getString("clearEntry","")!!.contains("UNKNOWN"))
    }
}
