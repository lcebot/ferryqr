package io.github.lcebot.ferryqr.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MagneticPoseTest {
    /** A pull as the pose maths reads it, at index 2 of one group. */
    private data class TestPull(
        override val finger: Float = 0f,
        override val phase: PullPhase = PullPhase.Pulling,
        override val key: Any = "pulled",
        override val index: Int = 2,
        override val rtl: Boolean = false,
        override val dismissing: Boolean = false,
        override val releaseVelocity: Float = 0f,
        override val group: Any = GROUP,
    ) : PullView

    private fun relationAt(index: Int, pull: PullView, key: Any = "row$index"): Relation =
        assertNotNull(rowRelation(pull, Triple(key, GROUP, index)))

    /** What [pull] gives the row right below it. */
    private fun below(pull: PullView): Contribution = contribution(pull, relationAt(pull.index + 1, pull), DETACH)

    private fun assertPose(expected: Pose, actual: Pose) {
        assertEquals(expected.lean, actual.lean, TOLERANCE, "lean")
        assertEquals(expected.backdrop, actual.backdrop, TOLERANCE, "backdrop")
        assertEquals(expected.topStart, actual.topStart, TOLERANCE, "top start")
        assertEquals(expected.topEnd, actual.topEnd, TOLERANCE, "top end")
        assertEquals(expected.bottomStart, actual.bottomStart, TOLERANCE, "bottom start")
        assertEquals(expected.bottomEnd, actual.bottomEnd, TOLERANCE, "bottom end")
    }

    @Test
    fun rowsRelateWithinThreeOfThePullInItsGroup() {
        val pull = TestPull()
        assertEquals(0.18f, relationAt(3, pull).towardEnd, TOLERANCE)
        assertEquals(0.06f, relationAt(0, pull).towardStart, TOLERANCE)
        // The rows three away only round, on the side facing the pull.
        val far = relationAt(5, pull)
        assertEquals(0f, far.towardEnd)
        assertEquals(Corners.TOP, far.corners)
        assertNull(rowRelation(pull, Triple("row6", GROUP, 6)))
        assertNull(rowRelation(pull, Triple("row3", "other", 3)))
        // The pulled row leans by nothing itself, while what is shown behind it does.
        val own = relationAt(9, pull, key = "pulled")
        assertEquals(0f, own.towardEnd)
        assertEquals(0.3f, own.backdrop, TOLERANCE)
        assertEquals(Corners.ALL, own.looseCorners)
    }

    @Test
    fun anAttachedPullLeansAndRoundsWithTheTravel() {
        val pose = below(TestPull(finger = 40f)).pose
        // 0.18 of the travel, and rounded 0.9 as strongly as the half of the travel to the detach point.
        assertPose(Pose(7.2f, 0f, 0.18f, 0.18f, 0.18f, 0.18f), pose)
    }

    @Test
    fun roundingBeforeDetachingStopsShortOfFull() {
        val pose = below(TestPull(finger = -190f)).pose
        assertEquals(-34.2f, pose.lean, TOLERANCE)
        assertEquals(0.9f * 0.8f, pose.topStart, TOLERANCE)
    }

    @Test
    fun aLoosePullHoldsItsNeighboursWhereItCameLoose() {
        val pose = below(TestPull(finger = 260f, phase = PullPhase.Detached)).pose
        // Only the corners facing the loose row round, and fully.
        assertPose(Pose(18f, 0f, 1f, 1f, 0f, 0f), pose)
        assertEquals(-18f, below(TestPull(finger = -90f, phase = PullPhase.Detached)).pose.lean, TOLERANCE)
    }

    @Test
    fun aReleaseLetsGoUnlessItFliesOff() {
        val back = below(TestPull(finger = 30f, phase = PullPhase.Released)).pose
        assertPose(Pose(0f, 0f, 0f, 0f, 0f, 0f), back)
        val off = below(TestPull(finger = 120f, phase = PullPhase.Released, dismissing = true)).pose
        assertPose(Pose(0f, 0f, 1f, 1f, 0f, 0f), off)
    }

    @Test
    fun theShareFollowsTheWayTheFingerGoes() {
        val relation = Relation(0.1f, 0.3f, 1f, Corners.ALL, Corners.ALL)
        assertEquals(0.1f, contribution(TestPull(finger = 10f), relation, DETACH).share)
        assertEquals(0.3f, contribution(TestPull(finger = -10f), relation, DETACH).share)
        // Right to left, a finger moving right moves toward the start.
        assertEquals(0.3f, contribution(TestPull(finger = 10f, rtl = true), relation, DETACH).share)
        assertEquals(0.1f, contribution(TestPull(finger = -10f, rtl = true), relation, DETACH).share)
    }

    @Test
    fun pullsTheSameWayAddUpToTheReach() {
        val reach = 0.18f * DETACH
        val one = below(TestPull(finger = 40f))
        val two = combine(listOf(one, below(TestPull(finger = 40f, key = "second"))), reach)
        assertEquals(14.4f, two.lean, TOLERANCE)
        val three = combine(List(3) { below(TestPull(finger = 40f, key = "pull$it")) }, reach)
        assertEquals(reach, three.lean, TOLERANCE)
        val back = combine(List(3) { below(TestPull(finger = -40f, key = "pull$it")) }, reach)
        assertEquals(-reach, back.lean, TOLERANCE)
    }

    @Test
    fun opposingPullsCancel() {
        val pose = combine(listOf(below(TestPull(finger = 40f)), below(TestPull(finger = -40f, key = "other"))), 18f)
        assertEquals(0f, pose.lean, TOLERANCE)
        // Each corner rounds as far as the pull that rounds it most, not further.
        assertEquals(0.18f, pose.topStart, TOLERANCE)
    }

    @Test
    fun cornersTakeTheRoundestPull() {
        val attached = below(TestPull(finger = 40f))
        val loose = below(TestPull(finger = 260f, phase = PullPhase.Detached, key = "loose"))
        val pose = combine(listOf(attached, loose), 18f)
        assertPose(Pose(18f, 0f, 1f, 1f, 0.18f, 0.18f), pose)
    }

    @Test
    fun theBackdropTakesNoLimit() {
        val pull = TestPull(finger = 100f)
        val own = contribution(pull, relationAt(pull.index, pull, key = "pulled"), DETACH)
        val pose = combine(listOf(own), 18f)
        assertEquals(0f, pose.lean)
        assertEquals(30f, pose.backdrop, TOLERANCE)
    }

    @Test
    fun aPullThatKeepsItsPhaseLeavesTheSpringsBe() {
        assertNull(respring(below(TestPull(finger = 10f)), below(TestPull(finger = 20f)), emptyList()))
    }

    @Test
    fun aPullArrivingPartWaySettlesTheEntryOnToIt() {
        val after = below(TestPull(finger = 40f))
        assertEquals(Respring(leans = true, quick = false, leanVelocity = null, backdropVelocity = null, rounds = true), respring(null, after, listOf(after)))
        val still = below(TestPull())
        assertEquals(Respring(leans = false, quick = false, leanVelocity = null, backdropVelocity = null, rounds = false), respring(null, still, listOf(still)))
    }

    @Test
    fun comingLooseSettlesAndRounds() {
        val after = below(TestPull(finger = 90f, phase = PullPhase.Detached))
        val change = assertNotNull(respring(below(TestPull(finger = 79f)), after, listOf(after)))
        assertEquals(Respring(leans = true, quick = false, leanVelocity = null, backdropVelocity = null, rounds = true), change)
    }

    @Test
    fun rejoiningAloneCatchesUpFromRest() {
        val after = below(TestPull(finger = 60f))
        val change = respring(below(TestPull(finger = 70f, phase = PullPhase.Detached)), after, listOf(after))
        assertEquals(Respring(leans = true, quick = true, leanVelocity = 0f, backdropVelocity = 0f, rounds = true), change)
    }

    @Test
    fun rejoiningBesideAnotherPullKeepsItsSpeed() {
        val after = below(TestPull(finger = 60f))
        val other = below(TestPull(finger = -30f, key = "other"))
        val change = respring(below(TestPull(finger = 70f, phase = PullPhase.Detached)), after, listOf(other, after))
        assertEquals(Respring(leans = true, quick = true, leanVelocity = null, backdropVelocity = null, rounds = true), change)
    }

    @Test
    fun aReleaseAloneSettlesFromTheSpeedItWasLetGoAt() {
        val pull = TestPull(finger = 90f, phase = PullPhase.Released, releaseVelocity = 1000f)
        val after = below(pull)
        val change = respring(below(TestPull(finger = 90f, phase = PullPhase.Detached)), after, listOf(after))
        assertEquals(Respring(leans = true, quick = false, leanVelocity = 180f, backdropVelocity = 0f, rounds = true), change.roundedOff())
        // What is shown behind the pulled entry settles at its own share of the speed.
        val ownRelation = relationAt(pull.index, pull, key = "pulled")
        val own = contribution(pull, ownRelation, DETACH)
        val ownChange = respring(contribution(pull.copy(phase = PullPhase.Detached), ownRelation, DETACH), own, listOf(own))
        assertEquals(0f, ownChange?.leanVelocity)
        assertEquals(300f, assertNotNull(ownChange?.backdropVelocity), TOLERANCE)
    }

    @Test
    fun aReleaseBesideAFingerStillPullingKeepsUpWithIt() {
        val after = below(TestPull(finger = 30f, phase = PullPhase.Released, releaseVelocity = 1000f))
        val other = below(TestPull(finger = 20f, key = "other"))
        val change = respring(below(TestPull(finger = 30f)), after, listOf(other, after))
        assertEquals(Respring(leans = true, quick = true, leanVelocity = null, backdropVelocity = null, rounds = true), change)
    }

    @Test
    fun aReleaseBesideALoosePullSettlesAtTheSpeedTheEntryHas() {
        val after = below(TestPull(finger = 30f, phase = PullPhase.Released, releaseVelocity = 1000f))
        val other = below(TestPull(finger = 200f, phase = PullPhase.Detached, key = "other"))
        val change = respring(below(TestPull(finger = 30f)), after, listOf(other, after))
        assertEquals(Respring(leans = true, quick = false, leanVelocity = null, backdropVelocity = null, rounds = true), change)
    }

    @Test
    fun aPullThatStopsBearingSettlesOnlyWhatItMoved() {
        val leaning = below(TestPull(finger = 40f))
        assertEquals(Respring(leans = true, quick = false, leanVelocity = null, backdropVelocity = null, rounds = true), respring(leaning, null, emptyList()))
        val other = below(TestPull(finger = 20f, key = "other"))
        assertTrue(assertNotNull(respring(leaning, null, listOf(other))).quick)
        val still = below(TestPull())
        val change = assertNotNull(respring(still, null, emptyList()))
        assertFalse(change.leans)
        assertFalse(change.rounds)
    }

    @Test
    fun aPullAcrossItsPhasesMovesTheEntryAsOne() {
        val reach = 0.18f * DETACH
        fun at(pull: TestPull) = combine(listOf(below(pull)), reach)
        assertEquals(9f, at(TestPull(finger = 50f)).lean, TOLERANCE)
        // Loose, the neighbour stays where it came loose however far the finger goes.
        assertEquals(18f, at(TestPull(finger = 100f, phase = PullPhase.Detached)).lean, TOLERANCE)
        assertEquals(18f, at(TestPull(finger = 300f, phase = PullPhase.Detached)).lean, TOLERANCE)
        // Rejoined, it follows the finger again.
        assertEquals(10.8f, at(TestPull(finger = 60f)).lean, TOLERANCE)
        assertEquals(0f, at(TestPull(finger = 60f, phase = PullPhase.Released)).lean)
    }

    /** The velocities to a hundredth, as the share times the speed comes out in floats. */
    private fun Respring?.roundedOff(): Respring? = this?.copy(
        leanVelocity = leanVelocity?.let { Math.round(it * 100f) / 100f },
        backdropVelocity = backdropVelocity?.let { Math.round(it * 100f) / 100f },
    )

    private companion object {
        const val GROUP = "group"
        const val DETACH = 100f
        const val TOLERANCE = 1e-4f
    }
}
