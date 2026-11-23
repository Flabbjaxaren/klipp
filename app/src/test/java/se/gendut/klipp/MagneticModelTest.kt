package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The magnetic timeline as pure logic: every edit on [MagTimeline] and the timeline it leaves. */
class MagneticModelTest {
    private fun v(id: Long, start: Float, len: Float, level: Int = 0, link: Long? = null, follows: Boolean = true) =
        MClip(id, true, level, start, len, link, follows)
    private fun a(id: Long, start: Float, len: Float, level: Int = 0, link: Long? = null) = MClip(id, false, level, start, len, link)

    /**
     * The storyline A (0–10), B (10–15), C (15–25), each with its linked sound on A1; connected: a cutaway R on V2
     * (2–5, on A), a title T on V2 (12–14, on B) and music M on A2 (0–25, hangs on A's first frame).
     */
    private fun base(side: MagSide = MagSide(), extra: List<MClip> = emptyList(), transitions: List<Pair<Long, Long>> = emptyList()) = MagTimeline(
        listOf(
            v(1, 0f, 10f, link = 1), a(101, 0f, 10f, link = 1),
            v(2, 10f, 5f, link = 2), a(102, 10f, 5f, link = 2),
            v(3, 15f, 10f, link = 3), a(103, 15f, 10f, link = 3),
            v(11, 2f, 3f, level = 1), v(10, 12f, 2f, level = 1, follows = false), a(20, 0f, 25f, level = 1),
        ) + extra,
        transitions, side,
    )

    private fun MPlan.at(id: Long): MClip = assertNotNull("clip $id is in the plan", clip(id)).let { clip(id)!! }
    private fun MPlan.start(id: Long) = at(id).start
    private fun near(expected: Float, actual: Float, what: String = "") = assertEquals(what, expected, actual, 1e-3f)

    // ------------------------------------------------------------------ what hangs where

    @Test fun connectedClipsHangOnTheClipUnderTheirFirstFrame() {
        val c = base().connections().associateBy { it.clip }
        assertEquals(1L, c[11]!!.parent)
        assertEquals(2L, c[10]!!.parent)
        assertEquals(1L, c[20]!!.parent)
        assertEquals(setOf(1L, 101L, 2L, 102L, 3L, 103L), base().primary)
    }

    @Test fun aMovedConnectionPointChangesTheParent() {
        // the music's connection point 16 s in: it hangs on C
        val c = base(MagSide(points = mapOf(20L to 16f))).connections().associateBy { it.clip }
        assertEquals(3L, c[20]!!.parent)
        near(16f, c[20]!!.at)
    }

    @Test fun aTrackEditedProjectOpensWithGapClipsAndAnchors() {
        // V1 with holes (as edited with tracks): the holes are gap clips; a title over a hole hangs on the gap clip
        val t = MagTimeline(listOf(v(1, 3f, 5f), v(2, 10f, 5f), v(9, 8.5f, 1f, level = 2)))
        assertEquals(listOf(MGap(0f, 3f), MGap(8f, 10f)), t.gaps())
        assertNull(t.connections().single().parent)
        assertEquals(MGap(8f, 10f), t.gapAt(9f))
        assertNull(t.gapAt(4f))
    }

    @Test fun connectedClipsPastTheEndHangOnATrailingGapClip() {
        val t = MagTimeline(listOf(v(1, 0f, 5f), v(9, 8f, 4f, level = 1)))
        assertEquals(listOf(MGap(5f, 12f)), t.gaps())
        near(12f, t.storyEnd)
        // an empty storyline: the gap clip starts at 0 (connecting into an empty timeline)
        assertEquals(listOf(MGap(0f, 6f)), MagTimeline(listOf(v(9, 2f, 4f, level = 1))).gaps())
    }

    @Test fun disconnectedClipsHangNowhere() {
        val c = base(MagSide(disconnected = setOf(11L))).connections()
        assertTrue(c.none { it.clip == 11L })
    }

    // ------------------------------------------------------------------ delete

    @Test fun deleteRipplesAndTakesConnectedClips() {
        val p = base().delete(setOf(2L))!!
        assertEquals(setOf(2L, 102L, 10L), p.removed)
        near(10f, p.start(3)); near(10f, p.start(103))
        near(2f, p.start(11)); near(0f, p.start(20))
    }

    @Test fun deleteKeepingConnectedClipsLeavesThemInPlace() {
        val p = base().delete(setOf(2L), keep = true)!!
        assertEquals(setOf(2L, 102L), p.removed)
        near(12f, p.start(10))
        assertEquals(1, p.at(10).level)
    }

    @Test fun deleteAConnectedClipMovesNothing() {
        val p = base().delete(setOf(10L))!!
        assertEquals(setOf(10L), p.removed)
        near(15f, p.start(3))
    }

    @Test fun deleteOnlyTheLinkedSoundDoesNotRipple() {
        val p = base().delete(setOf(102L))!!
        assertEquals(setOf(102L), p.removed)
        near(10f, p.start(2)); near(15f, p.start(3))
    }

    @Test fun disconnectedClipsSurviveTheirParent() {
        val p = base(MagSide(disconnected = setOf(11L))).delete(setOf(1L))!!
        assertFalse(11L in p.removed)
        near(2f, p.start(11))
        near(0f, p.start(2))
    }

    @Test fun deletingTheFirstClipTakesTheMusicHangingOnIt() {
        val p = base().delete(setOf(1L))!!
        assertTrue(20L in p.removed)
        assertTrue(11L in p.removed)
    }

    @Test fun nothingToDeleteIsNoEdit() = assertNull(base().delete(setOf(999L)))

    // ------------------------------------------------------------------ replace with gap

    @Test fun replaceWithGapMovesNothing() {
        val t = base()
        val p = t.replaceWithGap(setOf(2L))!!
        assertEquals(setOf(2L, 102L), p.removed)
        near(15f, p.start(3)); near(12f, p.start(10))
        // and the gap is a clip of the storyline now
        val after = MagTimeline(p.clips)
        assertEquals(listOf(MGap(10f, 15f)), after.gaps())
    }

    @Test fun replaceWithGapTakesJustTheLinkedSoundsAsked() {
        // a file's second and third audio streams (linked to its picture, on A2 and A3): Delete takes just them – the
        // picture and its first sound stay, nothing moves (it used to do nothing: the clips came back)
        val t = MagTimeline(listOf(v(1, 0f, 10f, link = 1), a(101, 0f, 10f, link = 1), a(201, 0f, 10f, level = 1, link = 1),
            a(301, 0f, 10f, level = 2, link = 1), v(2, 10f, 5f, link = 2), a(102, 10f, 5f, link = 2)))
        val p = t.replaceWithGap(setOf(201L, 301L)); assertNotNull("Delete does something", p); p!!
        assertEquals(setOf(201L, 301L), p.removed)
        near(0f, p.start(1)); near(0f, p.start(101)); near(10f, p.start(2)); near(10f, p.start(102))
        assertEquals(emptyList<MGap>(), MagTimeline(p.clips).gaps())
        // and with ripple (delete) the same: a sound alone leaves no hole in the storyline
        val d = t.delete(setOf(201L, 301L))!!
        assertEquals(setOf(201L, 301L), d.removed)
        near(10f, d.start(2))
    }

    // ------------------------------------------------------------------ magnetic moves

    @Test fun movingAClipToTheStartReordersAndConnectedClipsFollow() {
        val p = base().move(3, 0f, 0)!!
        near(0f, p.start(3)); near(10f, p.start(1)); near(20f, p.start(2))
        near(0f, p.start(103)); near(10f, p.start(101))
        // R and the music hang on A, T on B
        near(12f, p.start(11)); near(10f, p.start(20)); near(22f, p.start(10))
    }

    @Test fun movingToTheNearestEditPoint() {
        // A dropped at 9: without A the edit points are 0, 5 (B|C) and 15 – 5 is nearest
        val p = base().move(1, 9f, 0)!!
        near(0f, p.start(2)); near(5f, p.start(1)); near(15f, p.start(3))
        // a small drag stays where it is
        assertNull(base().move(1, 1.5f, 0))
    }

    @Test fun movingPastTheEndAppends() {
        val p = base().move(1, 100f, 0)!!
        near(0f, p.start(2)); near(5f, p.start(3)); near(15f, p.start(1))
    }

    @Test fun keepConnectedLeavesTheMovedClipsConnectedClipsInPlace() {
        val p = base().move(3, 0f, 0, keep = true)!!
        // C had nothing; A's R and music follow A as usual (only the moved clip's own stay)
        near(12f, p.start(11))
        val q = base().move(1, 100f, 0, keep = true)!!
        near(2f, q.start(11)); near(0f, q.start(20))
    }

    @Test fun movingOutOfTheStorylineMakesAConnectedClip() {
        val p = base().move(2, 30f, 1)!!
        // the storyline closes up
        near(10f, p.start(3))
        assertEquals(1, p.at(2).level); near(30f, p.start(2))
        // its sound goes as far out (A1 → A2), at the same time
        assertEquals(1, p.at(102).level); near(30f, p.start(102))
        // its title goes with it – and stacks above it (V2 is taken there)
        near(32f, p.start(10)); assertEquals(2, p.at(10).level)
    }

    @Test fun movingAConnectedClipIntoTheStorylineInserts() {
        val p = base().move(11, 9.5f, 0)!!
        assertEquals(0, p.at(11).level)
        near(10f, p.start(11)); near(13f, p.start(2)); near(18f, p.start(3))
        near(15f, p.start(10))
    }

    @Test fun aConnectedClipStacksWhenItsTrackIsTaken() {
        // R dropped over T on V2: it goes to V3
        val p = base().move(11, 12.5f, 1)!!
        near(12.5f, p.start(11)); assertEquals(2, p.at(11).level)
        // on a free stretch it stays on V2
        val q = base().move(11, 30f, 1)!!
        assertEquals(1, q.at(11).level)
    }

    @Test fun movedSoundStacksDownwards() {
        // a sound effect dropped on A1 (the storyline's sound is there): it goes to A2 – the music is there, so A3
        val p = base(extra = listOf(a(30, 40f, 2f, level = 3))).move(30, 5f, 0)!!
        assertEquals(2, p.at(30).level)
    }

    @Test fun stackingAddsATrackWhenAllAreTaken() {
        val extra = listOf(v(40, 0f, 30f, level = 2))
        val t = MagTimeline(base().clips + extra, videoLanes = 3)
        val p = t.move(11, 12.5f, 1)!!
        assertEquals(3, p.at(11).level)
        assertEquals(4, p.videoLanes)
    }

    @Test fun ripplingIntoAStillConnectedClipStacks() {
        // a cutaway on V2 over C (16–18) and one over A (6–9): delete B pulls C's cutaway onto A's
        val t = base(extra = listOf(v(50, 16f, 2f, level = 1), v(51, 6f, 6f, level = 1)))
        val p = t.delete(setOf(2L))!!
        near(11f, p.start(50))
        assertEquals(2, p.at(50).level)
        assertEquals(1, p.at(51).level)
    }

    @Test fun linkedSoundMovedAloneBecomesConnected() {
        val p = base().move(102, 30f, 2, alone = true)!!
        near(10f, p.start(2)); near(15f, p.start(3))
        near(30f, p.start(102)); assertEquals(2, p.at(102).level)
    }

    @Test fun blockMoveTakesStorylineClipsTogether() {
        val p = base().moveBlock(setOf(1L, 101L, 2L, 102L), 25f)!!
        near(0f, p.start(3)); near(10f, p.start(1)); near(20f, p.start(2))
    }

    @Test fun blockMoveOfConnectedClipsShifts() {
        val p = base().moveBlock(setOf(11L, 10L), 1f)!!
        near(3f, p.start(11)); near(13f, p.start(10))
        near(10f, p.start(2))
    }

    // ------------------------------------------------------------------ trims

    @Test fun trimOutRipplesLaterClips() {
        val p = base().trim(2, inEdge = false, delta = -2f)!!
        near(3f, p.at(2).len); near(3f, p.at(102).len)
        near(13f, p.start(3)); near(13f, p.start(103))
        // the title on B stays (2 s into B), the cutaway and music on A stay
        near(12f, p.start(10)); near(2f, p.start(11))
    }

    @Test fun trimOutLongerPushesOn() {
        val p = base().trim(1, inEdge = false, delta = 3f)!!
        near(13f, p.start(2)); near(18f, p.start(3)); near(15f, p.start(10))
    }

    @Test fun trimInCutsTheHeadAndConnectedClipsKeepTheirFrame() {
        // B's first second cut off: B stays at 10, C moves to 14; the title keeps to its frame of B (only if B's picture
        // runs: it does) → 11
        val p = base().trim(2, inEdge = true, delta = 1f)!!
        near(10f, p.start(2)); near(4f, p.at(2).len); near(1f, p.at(2).head); near(1f, p.at(102).head)
        near(14f, p.start(3)); near(11f, p.start(10))
    }

    @Test fun aConnectionPointCutAwayStaysOnTheFirstFrame() {
        val p = base().trim(2, inEdge = true, delta = 3f)!!
        near(10f, p.start(10))
    }

    @Test fun trimOnAFreezeFrameKeepsConnectedClipsOnItsStart() {
        val t = MagTimeline(listOf(v(1, 0f, 10f, follows = false), v(2, 4f, 2f, level = 1)))
        val p = t.trim(1, inEdge = true, delta = 1f)!!
        near(4f, p.start(2))
    }

    @Test fun trimWithKeepLeavesConnectedClipsInPlace() {
        val p = base().trim(1, inEdge = false, delta = -4f, keep = true)!!
        near(6f, p.start(2)); near(12f, p.start(10))
    }

    @Test fun trimAConnectedClipStopsAtItsNeighbor() {
        val t = base(extra = listOf(v(60, 14f, 3f, level = 1)))
        // T (12–14) ends where the clip after it starts: it cannot get longer
        assertNull(t.trim(10, inEdge = false, delta = 5f))
        assertNull(t.trim(10, inEdge = false, delta = 0f))
        near(1f, t.trim(10, inEdge = false, delta = -1f)!!.at(10).len)
        val q = t.trim(10, inEdge = true, delta = -1f)!!
        near(11f, q.start(10)); near(-1f, q.at(10).head)
        near(15f, q.start(3))
    }

    @Test fun trimTheLinkedSoundAloneDoesNotRipple() {
        val p = base().trim(102, inEdge = false, delta = -1f)!!
        near(4f, p.at(102).len); near(5f, p.at(2).len); near(15f, p.start(3))
    }

    // ------------------------------------------------------------------ gap clips

    private fun gapped() = MagTimeline(listOf(v(1, 0f, 10f), v(2, 15f, 5f), v(3, 20f, 5f), v(9, 11f, 2f, level = 1), v(8, 21f, 1f, level = 1)))

    @Test fun gapClipTrimRipples() {
        val p = gapped().trimGap(10f, -2f)!!
        near(13f, p.start(2)); near(18f, p.start(3))
        // the title on the gap keeps its place in it, the one on C moves with C
        near(11f, p.start(9)); near(19f, p.start(8))
        val q = gapped().trimGap(10f, 3f)!!
        near(18f, q.start(2))
        // as short as nothing at most
        near(10f, gapped().trimGap(10f, -50f)!!.start(2))
        assertNull(gapped().trimGap(3f, 1f))
    }

    @Test fun gapClipDeleteClosesItAndTakesItsConnectedClips() {
        val p = gapped().delete(emptySet(), gaps = listOf(10f))!!
        near(10f, p.start(2)); near(15f, p.start(3))
        assertEquals(setOf(9L), p.removed)
    }

    @Test fun neighboringGapsBecomeOne() {
        val p = gapped().replaceWithGap(setOf(2L))!!
        assertEquals(listOf(MGap(10f, 20f)), MagTimeline(p.clips).gaps())
    }

    // ------------------------------------------------------------------ position mode

    @Test fun positionMoveOverwritesAndLeavesAGap() {
        val p = base().positionMove(2, 20f)!!
        near(20f, p.start(2)); near(20f, p.start(102))
        // C keeps 15–20
        near(15f, p.start(3)); near(5f, p.at(3).len); near(5f, p.at(103).len)
        // its title went with it; the rest stays
        near(22f, p.start(10)); near(2f, p.start(11))
        assertEquals(listOf(MGap(10f, 15f)), MagTimeline(p.clips).gaps())
    }

    @Test fun positionMoveCutsTheHeadOfWhatItLandsOn() {
        val p = base().positionMove(1, 3f)!!
        near(3f, p.start(1))
        // B keeps 13–15: its head is cut, it stays where it is
        near(13f, p.start(2)); near(2f, p.at(2).len); near(3f, p.at(2).head)
        // the title was on the covered part: it stays; R went with A
        near(12f, p.start(10)); near(5f, p.start(11))
        assertEquals(listOf(MGap(0f, 3f)), MagTimeline(p.clips).gaps())
    }

    @Test fun positionMoveIntoTheMiddleCutsTheClipInTwo() {
        val p = base().positionMove(2, 2f)!!
        near(2f, p.at(1).len); near(2f, p.at(101).len)
        near(2f, p.start(2))
        assertEquals(2, p.pieces.size)
        val vp = p.pieces.first { it.from == 1L }
        near(7f, vp.at)
        near(7f, p.start(vp.id)); near(3f, p.at(vp.id).len); assertEquals(0, p.at(vp.id).level)
        val ap = p.pieces.first { it.from == 101L }
        assertTrue(!p.at(ap.id).video)
        near(15f, p.start(3))
        assertEquals(listOf(MGap(10f, 15f)), MagTimeline(p.clips).gaps())
    }

    @Test fun positionMovePastTheEndLeavesGaps() {
        val p = base().positionMove(2, 40f)!!
        near(40f, p.start(2)); near(15f, p.start(3))
        assertEquals(listOf(MGap(10f, 15f), MGap(25f, 40f)), MagTimeline(p.clips).gaps())
    }

    @Test fun positionMoveByLessThanItsLength() {
        val p = base().positionMove(2, 12f)!!
        near(12f, p.start(2))
        near(17f, p.start(3)); near(8f, p.at(3).len); near(2f, p.at(3).head)
        assertEquals(listOf(MGap(10f, 12f)), MagTimeline(p.clips).gaps())
    }

    // ------------------------------------------------------------------ insert, append, connect

    /** The base with a new clip N (and its sound) placed after everything, as the engine puts new media first. */
    private fun withNew() = base(extra = listOf(v(50, 40f, 4f, link = 50), a(150, 40f, 4f, link = 50)))

    @Test fun insertCutsTheClipAtThePlayheadAndRipples() {
        val p = withNew().insert(listOf(50), 5f)!!
        near(0f, p.start(1)); near(5f, p.at(1).len)
        near(5f, p.start(50)); near(5f, p.start(150))
        val piece = p.pieces.first { it.from == 1L }
        near(5f, piece.at); near(9f, p.start(piece.id)); near(5f, p.at(piece.id).len)
        assertNotNull(p.pieces.firstOrNull { it.from == 101L })
        near(14f, p.start(2)); near(19f, p.start(3))
        // R (2–5) stays on A's first part, the title on B moves with B
        near(2f, p.start(11)); near(16f, p.start(10)); near(0f, p.start(20))
    }

    @Test fun insertAtAnEditPointDoesNotCut() {
        val p = withNew().insert(listOf(50), 10f)!!
        assertTrue(p.pieces.isEmpty())
        near(10f, p.start(50)); near(14f, p.start(2))
    }

    @Test fun insertConnectedClipsOnTheCutPartMoveWithIt() {
        val t = base(extra = listOf(v(50, 40f, 4f), v(70, 7f, 1f, level = 2)))
        val p = t.insert(listOf(50), 5f)!!
        near(11f, p.start(70))
    }

    @Test fun appendGoesToTheEndOfTheStoryline() {
        val p = withNew().append(listOf(50))!!
        near(25f, p.start(50)); near(25f, p.start(150))
        assertTrue(p.removed.isEmpty())
    }

    @Test fun appendGoesAfterTheGapClipUnderClipsPastTheEnd() {
        val t = base(extra = listOf(v(80, 30f, 5f, level = 1), v(50, 40f, 4f)))
        val p = t.append(listOf(50))!!
        near(35f, p.start(50)); near(30f, p.start(80))
    }

    @Test fun connectFindsTheFirstFreeTrack() {
        val t = base()
        assertEquals(2, t.connectLevel(true, 12.5f, 1f))
        assertEquals(1, t.connectLevel(true, 6f, 3f))
        assertEquals(2, t.connectLevel(false, 3f, 2f))
        val full = MagTimeline((1..5).map { v(200L + it, 0f, 10f, level = it) })
        assertNull(full.connectLevel(true, 2f, 1f))
    }

    // ------------------------------------------------------------------ lift / overwrite to primary

    @Test fun liftFromStorylineLeavesAGapAndConnectsTheClip() {
        val p = base().liftFromStoryline(2)!!
        near(10f, p.start(2)); assertTrue(p.at(2).level >= 1)
        // the title was on V2 over it: the lifted clip goes on V3
        assertEquals(2, p.at(2).level)
        // its sound below the music (A2 is taken)
        assertEquals(2, p.at(102).level)
        near(15f, p.start(3)); near(12f, p.start(10)); assertEquals(1, p.at(10).level)
        assertEquals(listOf(MGap(10f, 15f)), MagTimeline(p.clips).gaps())
    }

    @Test fun overwriteToPrimaryKeepsTheCoveredSoundConnected() {
        val p = base().overwriteToPrimary(11)!!
        assertEquals(0, p.at(11).level); near(2f, p.start(11))
        near(2f, p.at(1).len)
        val vp = p.pieces.single { it.from == 1L }
        near(5f, p.start(vp.id)); near(5f, p.at(vp.id).len)
        // A's sound under R (2–5) stays, on A1 (R brings none)
        val mid = p.pieces.first { it.from == 101L && kotlin.math.abs(it.at - 2f) < 1e-3f }
        near(2f, p.start(mid.id)); near(3f, p.at(mid.id).len); assertEquals(0, p.at(mid.id).level)
        near(10f, p.start(2))
    }

    @Test fun overwriteToPrimaryWithSoundMovesTheCoveredSoundDown() {
        val t = base(extra = listOf(v(60, 11f, 3f, level = 1, link = 60), a(160, 11f, 3f, level = 1, link = 60)))
        val p = t.overwriteToPrimary(60)!!
        assertEquals(0, p.at(60).level); assertEquals(0, p.at(160).level)
        // B's sound under it went to a free track below (A2 has the music: A3)
        val mid = p.pieces.first { it.from == 102L && kotlin.math.abs(it.at - 11f) < 1e-3f }
        assertEquals(2, p.at(mid.id).level)
        near(15f, p.start(3))
    }

    @Test fun overwriteToPrimaryRefusesSoundAndStorylineClips() {
        assertNull(base().overwriteToPrimary(20))
        assertNull(base().overwriteToPrimary(2))
    }

    // ------------------------------------------------------------------ connected storylines (transitions)

    @Test fun clipsJoinedByATransitionMoveTogether() {
        // X (13–15, on B) and Y (15–17, on C) with a transition between them: one group, hanging on B
        val extra = listOf(v(70, 13f, 2f, level = 2), v(71, 15f, 2f, level = 2))
        val joined = base(extra = extra, transitions = listOf(70L to 71L)).trim(2, inEdge = false, delta = -1f)!!
        near(13f, joined.start(70)); near(15f, joined.start(71))
        // without the transition Y follows C
        val apart = base(extra = extra).trim(2, inEdge = false, delta = -1f)!!
        near(14f, apart.start(71))
    }

    // ------------------------------------------------------------------ captions

    private fun captioned() = MagTimeline(base().clips, captions = listOf(MCaption(500, 1f, 3f), MCaption(501, 11f, 13f), MCaption(502, 17f, 19f)))

    @Test fun captionsStayOnTheirWords() {
        // B deleted: its caption goes with it, C's moves back with C, A's stays
        val p = captioned().delete(setOf(2L))!!
        assertEquals(setOf(501L), p.captionsRemoved)
        assertEquals(mapOf(502L to -5f), p.captionShift)
        // C moved to the start: its caption with it, A's and B's on
        val q = captioned().move(3, 0f, 0)!!
        near(-15f, q.captionShift[502L]!!); near(10f, q.captionShift[500L]!!); near(10f, q.captionShift[501L]!!)
        // a ripple trim of B's head: B's caption keeps to its frame
        val r = captioned().trim(2, inEdge = true, delta = 1f)!!
        near(-1f, r.captionShift[501L]!!); near(-1f, r.captionShift[502L]!!)
        assertFalse(500L in r.captionShift)
    }

    @Test fun captionsDoNotTakeTracksOrConnections() {
        val t = captioned()
        assertTrue(t.connections().none { it.clip in setOf(500L, 501L, 502L) })
        val p = t.move(11, 30f, 1)!!
        assertTrue(p.clips.none { it.id >= 500L })
        assertTrue(p.captionShift.isEmpty())
    }

    // ------------------------------------------------------------------ side state

    @Test fun keepOnlyDropsDeletedClips() {
        val s = MagSide(setOf(1L, 2L), mapOf(1L to 2f, 3L to 1f))
        assertEquals(MagSide(setOf(1L), mapOf(1L to 2f)), s.keepOnly(setOf(1L)))
    }
}
