package bg.arduino.uploader;

public final class BoardProfile {
    public enum Board {
        UNO,
        MEGA2560
    }

    public static Board detect(UsbDeviceInfo device) {
        if (device == null) return Board.MEGA2560;
        // Arduino Uno: VID 2341 or 1A86, Mega: VID 2341. PID is deliberately
        // not used alone because clones reuse it across board families.
        int vid = device.vendorId;
        if (vid == 0x2341 || vid == 0x1A86 || vid == 0x0403) {
            String name = device.name.toLowerCase();
            if (name.contains("uno") || name.contains("328") || name.contains("ch340")
                    || name.contains("atmega16u2") || name.contains("usb-serial")) {
                return Board.UNO;
            }
        }
        return Board.MEGA2560;
    }
}
