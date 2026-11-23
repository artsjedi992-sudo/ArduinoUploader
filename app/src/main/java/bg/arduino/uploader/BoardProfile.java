package bg.arduino.uploader;

/** Explicit board profiles; USB VID/PID alone cannot reliably distinguish UNO from Mega clones. */
public final class BoardProfile {
    private BoardProfile() {}
    public enum Board {
        UNO("Arduino UNO (ATmega328P)", 32 * 1024, 128),
        MEGA2560("Arduino Mega 2560 (ATmega2560)", 256 * 1024, 256);
        public final String label;
        public final int flashBytes;
        public final int pageBytes;
        Board(String label, int flashBytes, int pageBytes) {
            this.label = label; this.flashBytes = flashBytes; this.pageBytes = pageBytes;
        }
    }
}
