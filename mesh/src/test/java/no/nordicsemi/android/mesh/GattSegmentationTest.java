package no.nordicsemi.android.mesh;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

/**
 * Tests for {@link MeshManagerApi#removeSegmentation(int, byte[])} — reassembly of
 * proxy PDUs segmented at the GATT layer (Mesh Profile 6.3.1).
 *
 * Each GATT notification carries a 1-octet header (SAR bits + PDU type) followed by
 * payload. The reassembled PDU keeps a single header octet (SAR bits masked off)
 * followed by the concatenated payloads.
 */
public class GattSegmentationTest {

    private static final int MTU_SIZE = 20; // ATT MTU 23 → max packet 20
    private static final byte PDU_TYPE_NETWORK = 0x00;
    private static final byte SAR_START = (byte) 0b01000000;
    private static final byte SAR_CONTINUATION = (byte) 0b10000000;
    private static final byte SAR_END = (byte) 0b11000000;

    /**
     * Builds the concatenated notification stream a proxy sends for the given payload,
     * split into per-notification payload chunks of (mtuSize - 1) bytes.
     *
     * @param trailingEmptyEnd when true, the payload fills whole CONTINUATION segments
     *                         and the END segment carries only the header octet — the
     *                         shape observed in the field (Sentry issue 7480698932).
     */
    private static byte[] segmentedStream(final byte[] payload, final int mtuSize,
                                          final boolean trailingEmptyEnd) {
        final int chunkPayload = mtuSize - 1;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        boolean first = true;
        while (offset < payload.length) {
            final int len = Math.min(payload.length - offset, chunkPayload);
            final boolean last = !trailingEmptyEnd && offset + len == payload.length;
            out.write((first ? SAR_START : (last ? SAR_END : SAR_CONTINUATION)) | PDU_TYPE_NETWORK);
            out.write(payload, offset, len);
            offset += len;
            first = false;
        }
        if (trailingEmptyEnd) {
            out.write(SAR_END | PDU_TYPE_NETWORK);
        }
        return out.toByteArray();
    }

    /** Expected reassembly result: one type octet followed by the payload. */
    private static byte[] expectedPdu(final byte[] payload) {
        final byte[] expected = new byte[payload.length + 1];
        expected[0] = PDU_TYPE_NETWORK;
        System.arraycopy(payload, 0, expected, 1, payload.length);
        return expected;
    }

    private static byte[] payloadOfLength(final int length) {
        final byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
            payload[i] = (byte) (i + 1);
        }
        return payload;
    }

    /**
     * Regression test for the production crash (Sentry issue 7480698932):
     * a 57-byte payload sent as three full 20-byte notifications plus a 1-byte END
     * segment carrying only the SAR octet — a 61-byte combined stream at MTU 23.
     * The broken offset arithmetic read past the end of the source array:
     * ArrayIndexOutOfBoundsException: src.length=61 srcPos=61 dst.length=58 dstPos=57.
     */
    @Test
    public void removeSegmentation_reassemblesStreamWithEmptyEndSegment() {
        final byte[] payload = payloadOfLength(57);
        final byte[] stream = segmentedStream(payload, MTU_SIZE, true);
        assertArrayEquals(new byte[]{61}, new byte[]{(byte) stream.length}); // guard: exact crash shape

        final byte[] reassembled = MeshManagerApi.removeSegmentation(MTU_SIZE, stream);

        assertArrayEquals(expectedPdu(payload), reassembled);
    }

    @Test
    public void removeSegmentation_reassemblesStandardThreeSegmentStream() {
        final byte[] payload = payloadOfLength(57); // 3 segments of 19, END carries payload
        final byte[] stream = segmentedStream(payload, MTU_SIZE, false);

        final byte[] reassembled = MeshManagerApi.removeSegmentation(MTU_SIZE, stream);

        assertArrayEquals(expectedPdu(payload), reassembled);
    }

    @Test
    public void removeSegmentation_reassemblesAllPayloadLengths() {
        for (int length = 20; length <= 200; length++) {
            final byte[] payload = payloadOfLength(length);

            final byte[] standard = MeshManagerApi.removeSegmentation(
                    MTU_SIZE, segmentedStream(payload, MTU_SIZE, false));
            assertArrayEquals("standard stream, payload length " + length,
                    expectedPdu(payload), standard);

            if (length % (MTU_SIZE - 1) == 0) {
                final byte[] degenerate = MeshManagerApi.removeSegmentation(
                        MTU_SIZE, segmentedStream(payload, MTU_SIZE, true));
                assertArrayEquals("empty-END stream, payload length " + length,
                        expectedPdu(payload), degenerate);
            }
        }
    }
}
