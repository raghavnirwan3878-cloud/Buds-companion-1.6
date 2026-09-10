package com.budscompanion.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Ported/re-implemented from Gadgetbridge's
 * nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.OppoHeadphonesProtocol
 * (AGPLv3, Copyright (C) 2024 José Rebelo).
 *
 * Wire format (all multi-byte fields little-endian):
 *   byte0    : preamble (0xAA)
 *   byte1    : totalLength = (frame length - 2)
 *   byte2-3  : reserved (usually 0x0000, sometimes 0x0004 on Realme)
 *   byte4-5  : command code (short)
 *   byte6    : sequence number
 *   byte7-8  : payload length (short)
 *   byte9..  : payload
 */
public class OppoProtocol {

    public static final byte PREAMBLE = (byte) 0xAA;

    // Command codes (from OppoCommand.java)
    public static final short CMD_BATTERY_REQ = 0x0106;
    public static final short CMD_BATTERY_RET = (short) 0x8106;
    public static final short CMD_SUBSCRIPTION_SET = 0x0205;
    public static final short CMD_SUBSCRIPTION_ACK = (short) 0x8205;
    public static final short CMD_SUBSCRIPTION_RET = 0x0204;
    public static final short CMD_FIRMWARE_GET = 0x0105;
    public static final short CMD_FIRMWARE_RET = (short) 0x8105;
    public static final short CMD_FIND_DEVICE_REQ = 0x0400;
    public static final short CMD_FIND_DEVICE_ACK = (short) 0x8400;

    // Subscription types (from SubscriptionType.java)
    public static final int SUB_BATTERY = 0x01;
    public static final int SUB_STATUS = 0x02;
    public static final int SUB_ANC_SELECTOR = 0x03;
    public static final int SUB_GAME_MODE = 0x05;

    private int seqNum = 0;

    /** Battery slot: 0 = left earbud, 1 = right earbud, 2 = case */
    public static class BatteryInfo {
        public final int index;
        public final int level;
        public final boolean charging;

        BatteryInfo(int index, int level, boolean charging) {
            this.index = index;
            this.level = level;
            this.charging = charging;
        }

        @Override
        public String toString() {
            String slot = index == 0 ? "Left" : index == 1 ? "Right" : "Case";
            return slot + "=" + level + "%" + (charging ? " (charging)" : "");
        }
    }

    /** Build the message that requests a one-off battery read. */
    public byte[] encodeBatteryReq() {
        return encodeMessage(CMD_BATTERY_REQ, new byte[0]);
    }

    /**
     * Build the message that subscribes to push updates for the given types.
     * The buds will then send BATTERY_RET/SUBSCRIPTION_RET on their own
     * whenever battery (or other subscribed state) changes, without needing
     * to poll.
     */
    public byte[] encodeSubscriptionSet(int... subscriptionTypeCodes) {
        byte[] payload = new byte[1 + subscriptionTypeCodes.length];
        payload[0] = 0x09; // constant observed in Gadgetbridge's implementation
        for (int i = 0; i < subscriptionTypeCodes.length; i++) {
            payload[i + 1] = (byte) subscriptionTypeCodes[i];
        }
        return encodeMessage(CMD_SUBSCRIPTION_SET, payload);
    }

    public byte[] encodeFirmwareReq() {
        return encodeMessage(CMD_FIRMWARE_GET, new byte[0]);
    }

    /** Make the buds play a "find me" sound. Pass false to stop it early. */
    public byte[] encodeFindDevice(boolean start) {
        return encodeMessage(CMD_FIND_DEVICE_REQ, new byte[]{(byte) (start ? 0x01 : 0x00)});
    }

    private byte[] encodeMessage(short command, byte[] payload) {
        final ByteBuffer buf = ByteBuffer.allocate(9 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(PREAMBLE);
        buf.put((byte) (buf.limit() - 2));
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putShort(command);
        buf.put((byte) (seqNum++ & 0xFF));
        buf.putShort((short) payload.length);
        buf.put(payload);
        return buf.array();
    }

    /**
     * Incremental frame reader: feed it raw bytes as they arrive from the
     * socket's InputStream; it buffers internally and returns any complete
     * frames it can extract (there may be more than one per call, or zero if
     * a frame is still incomplete).
     */
    public static class FrameReader {
        private byte[] buffer = new byte[0];

        public synchronized List<byte[]> feed(byte[] data, int len) {
            byte[] combined = new byte[buffer.length + len];
            System.arraycopy(buffer, 0, combined, 0, buffer.length);
            System.arraycopy(data, 0, combined, buffer.length, len);
            buffer = combined;

            List<byte[]> frames = new ArrayList<>();
            int pos = 0;
            while (pos < buffer.length) {
                if (buffer[pos] != PREAMBLE) {
                    // resync: drop one byte and keep scanning
                    pos++;
                    continue;
                }
                if (pos + 2 > buffer.length) {
                    break; // need more data for the length byte
                }
                int totalLength = buffer[pos + 1] & 0xFF;
                int frameLen = totalLength + 2;
                if (pos + frameLen > buffer.length) {
                    break; // incomplete frame, wait for more data
                }
                byte[] frame = new byte[frameLen];
                System.arraycopy(buffer, pos, frame, 0, frameLen);
                frames.add(frame);
                pos += frameLen;
            }

            // keep any leftover unparsed bytes for next time
            byte[] remainder = new byte[buffer.length - pos];
            System.arraycopy(buffer, pos, remainder, 0, remainder.length);
            buffer = remainder;

            return frames;
        }
    }

    /** Parsed result of a single frame. */
    public static class Decoded {
        public short command;
        public List<BatteryInfo> batteries = new ArrayList<>();
        /** True if this frame is the device confirming our SUBSCRIPTION_SET request. */
        public boolean isSubscriptionAck = false;
        /** True if this frame is a battery update the device pushed on its own
         *  (SUBSCRIPTION_RET), as opposed to one we explicitly asked for
         *  (BATTERY_RET). Lets the app tell whether push updates are working. */
        public boolean isPushedUpdate = false;
    }

    /**
     * Parse one complete frame (as produced by FrameReader). Returns null if
     * the frame is malformed or not battery-related (callers only care about
     * battery here, but command is exposed for debugging/extension).
     */
    public static Decoded decodeFrame(byte[] frame) {
        if (frame.length < 9 || frame[0] != PREAMBLE) {
            return null;
        }
        final ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        buf.get(); // preamble
        int totalLength = buf.get() & 0xFF;
        if (frame.length != totalLength + 2) {
            return null;
        }
        buf.getShort(); // reserved (0 or 4)
        short command = buf.getShort();
        buf.get(); // seq
        int payloadLength = buf.getShort() & 0xFFFF;
        if (payloadLength > buf.remaining()) {
            return null;
        }
        byte[] payload = new byte[payloadLength];
        buf.get(payload);

        Decoded decoded = new Decoded();
        decoded.command = command;

        if (command == CMD_BATTERY_RET) {
            if (payload.length > 0 && payload[0] == 0) {
                decoded.batteries = parseBattery(payload);
            }
        } else if (command == CMD_SUBSCRIPTION_RET) {
            if (payload.length > 0 && (payload[0] & 0xFF) == SUB_BATTERY) {
                decoded.batteries = parseBattery(payload);
                decoded.isPushedUpdate = true;
            }
        } else if (command == CMD_SUBSCRIPTION_ACK) {
            decoded.isSubscriptionAck = true;
        }

        return decoded;
    }

    /**
     * Shared battery-payload parser used for both BATTERY_RET and
     * SUBSCRIPTION_RET(BATTERY) frames, matching Gadgetbridge's parseBattery().
     * payload[0] = status/type byte (ignored here)
     * payload[1] = battery count (informational only)
     * payload[2..] = pairs of (slotByte, levelByte)
     *   slotByte: 1=left, 2=right, 3=case (subtract 1 to get index 0/1/2); 0xFF = not present
     *   levelByte: bits 0-6 = percentage, bit 7 = charging flag
     *
     * Only returns entries for slots actually mentioned in this frame - a
     * slot the device doesn't mention here is left untouched by the caller,
     * rather than being wiped just because one particular update happened
     * not to include it. A slot explicitly reported as 0% IS included (with
     * level=0), since that's the device actively saying "not present now"
     * (case away/closed, earbud out) - callers should treat level<=0 as
     * "hide this row".
     */
    private static List<BatteryInfo> parseBattery(byte[] payload) {
        List<BatteryInfo> result = new ArrayList<>();

        for (int i = 2; i + 1 < payload.length; i += 2) {
            int slotByte = payload[i] & 0xFF;
            if (slotByte == 0xFF) {
                continue;
            }
            int index = slotByte - 1;
            if (index < 0 || index > 2) {
                continue;
            }
            int level = payload[i + 1] & 0x7F;
            boolean isCharging = (payload[i + 1] & 0x80) != 0;
            result.add(new BatteryInfo(index, level, isCharging));
        }

        return result;
    }
}
