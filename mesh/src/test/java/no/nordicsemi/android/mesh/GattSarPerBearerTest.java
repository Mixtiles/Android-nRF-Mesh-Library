package no.nordicsemi.android.mesh;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for per-bearer GATT SAR reassembly — {@link MeshManagerApi#appendPdu(MeshManagerApi.GattSarState, int, byte[])}.
 *
 * Field failure this pins (oasis-app, 2026-09-06): the app held two GATT links at
 * once — a PB-GATT link in proxy mode to a node being configured, and a proxy link
 * to an already-provisioned node that relays every reply from the first. Both fed
 * {@code handleNotifications} at ATT MTU 23, so every ~31-byte network PDU arrived
 * as START+END on each link. With a single shared buffer, interleaved segments were
 * glued into a 47-byte "network PDU" that failed decryption silently, and the real
 * END segment was left orphaned in the buffer. The Config Model Subscription Status
 * was lost on both paths and the config phase timed out.
 */
public class GattSarPerBearerTest {

    private static final int MTU_SIZE = 20; // ATT MTU 23 → max packet 20
    private static final byte PDU_TYPE_NETWORK = 0x00;
    private static final byte SAR_START = (byte) 0b01000000;
    private static final byte SAR_CONTINUATION = (byte) 0b10000000;
    private static final byte SAR_END = (byte) 0b11000000;

    /** Splits a payload into the per-notification segments a proxy sends at [mtuSize]. */
    private static List<byte[]> segments(final byte[] payload, final int mtuSize) {
        final int chunkPayload = mtuSize - 1;
        final List<byte[]> out = new ArrayList<>();
        int offset = 0;
        boolean first = true;
        while (offset < payload.length) {
            final int len = Math.min(payload.length - offset, chunkPayload);
            final boolean last = offset + len == payload.length;
            final byte[] seg = new byte[len + 1];
            seg[0] = (byte) ((first ? SAR_START : (last ? SAR_END : SAR_CONTINUATION)) | PDU_TYPE_NETWORK);
            System.arraycopy(payload, offset, seg, 1, len);
            out.add(seg);
            offset += len;
            first = false;
        }
        return out;
    }

    private static byte[] expectedPdu(final byte[] payload) {
        final byte[] expected = new byte[payload.length + 1];
        expected[0] = PDU_TYPE_NETWORK;
        System.arraycopy(payload, 0, expected, 1, payload.length);
        return expected;
    }

    private static byte[] payloadOfLength(final int length, final int seed) {
        final byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
            payload[i] = (byte) (seed + i);
        }
        return payload;
    }

    /** Feeds one segment; returns the reassembled PDU when it completes, else null. */
    private static byte[] feed(final MeshManagerApi.GattSarState state, final byte[] segment) {
        final byte[] combined = MeshManagerApi.appendPdu(state, MTU_SIZE, segment);
        return combined == null ? null : MeshManagerApi.removeSegmentation(MTU_SIZE, combined);
    }

    /** Exact field shape: two 30-byte network PDUs (START+END each) interleaved across two bearers. */
    @Test
    public void interleavedSegmentsFromTwoBearersReassembleIndependently() {
        final MeshManagerApi.GattSarState pbGatt = new MeshManagerApi.GattSarState();
        final MeshManagerApi.GattSarState proxy = new MeshManagerApi.GattSarState();
        final byte[] direct = payloadOfLength(30, 0x10);   // reply over PB-GATT
        final byte[] relayed = payloadOfLength(30, 0x50);  // relayed copy via the other proxy
        final List<byte[]> a = segments(direct, MTU_SIZE);
        final List<byte[]> b = segments(relayed, MTU_SIZE);

        assertNull(feed(pbGatt, a.get(0)));              // A START
        assertNull(feed(proxy, b.get(0)));               // B START (lands mid-A)
        assertArrayEquals(expectedPdu(relayed), feed(proxy, b.get(1)));  // B END
        assertArrayEquals(expectedPdu(direct), feed(pbGatt, a.get(1)));  // A END
    }

    @Test
    public void threeSegmentPduOnOneBearerStillReassembles() {
        final MeshManagerApi.GattSarState state = new MeshManagerApi.GattSarState();
        final byte[] payload = payloadOfLength(57, 1);
        final List<byte[]> segs = segments(payload, MTU_SIZE);

        assertNull(feed(state, segs.get(0)));
        assertNull(feed(state, segs.get(1)));
        assertArrayEquals(expectedPdu(payload), feed(state, segs.get(2)));
    }

    /** Mirrors iOS ProxyProtocolHandler: a new START discards a stale partial message. */
    @Test
    public void startWhileBufferedDiscardsStalePartial() {
        final MeshManagerApi.GattSarState state = new MeshManagerApi.GattSarState();
        final byte[] stale = payloadOfLength(57, 1);
        final byte[] fresh = payloadOfLength(30, 0x70);

        assertNull(feed(state, segments(stale, MTU_SIZE).get(0)));  // START, never completed
        final List<byte[]> f = segments(fresh, MTU_SIZE);
        assertNull(feed(state, f.get(0)));                           // new START
        assertArrayEquals(expectedPdu(fresh), feed(state, f.get(1)));
    }

    /** An END/CONTINUATION with nothing buffered is dropped and must not poison the next message. */
    @Test
    public void orphanSegmentIsDroppedAndDoesNotPoisonNextMessage() {
        final MeshManagerApi.GattSarState state = new MeshManagerApi.GattSarState();
        final byte[] orphan = payloadOfLength(30, 0x20);
        final byte[] next = payloadOfLength(30, 0x30);

        assertNull(feed(state, segments(orphan, MTU_SIZE).get(1)));  // END, no START seen
        final List<byte[]> n = segments(next, MTU_SIZE);
        assertNull(feed(state, n.get(0)));
        assertArrayEquals(expectedPdu(next), feed(state, n.get(1)));
    }
}
