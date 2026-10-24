package bg.arduino.uploader;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.IOException;
import java.util.Arrays;

/**
 * Минимална реализация на STK500v2 за стандартния bootloader на Arduino Mega 2560.
 * Програмирането трябва да се провери на реалната платка преди използване за работа.
 */
public final class Stk500v2Programmer {
    public interface Progress { void update(int written, int total); }

    private static final int MESSAGE_START = 0x1B;
    private static final int TOKEN = 0x0E;
    private static final int SIGN_ON = 0x01;
    private static final int ENTER_PROGMODE_ISP = 0x10;
    private static final int LEAVE_PROGMODE_ISP = 0x11;
    private static final int LOAD_ADDRESS = 0x06;
    private static final int PROGRAM_FLASH_ISP = 0x13;
    private static final int STATUS_OK = 0x00;

    private final UsbSerialPort port;
    private int sequence = 0;

    public Stk500v2Programmer(UsbSerialPort port) { this.port = port; }

    /** Извършва handshake, записва страници и излиза от режима за програмиране. */
    public void upload(HexParser.Image image, Progress progress) throws Exception {
        byte[] signOn = command(SIGN_ON, new byte[0]);
        if (signOn.length < 3 || (signOn[1] & 0xFF) != STATUS_OK)
            throw new IOException("Платката не отговори на STK500v2 sign-on.");
        if (signOn.length >= 11) {
            String id = new String(signOn, 3, Math.min(8, signOn.length - 3));
            if (!id.startsWith("STK500_2"))
                throw new IOException("Неочакван bootloader: " + id);
        }

        byte[] enter = {0x0A, 0x19, 0x00, 0x00, 0x00, 0x53, 0x03, (byte)0xAC, 0x53, 0x00, 0x00};
        checkStatus(command(ENTER_PROGMODE_ISP, enter), "Влизане в режим за програмиране");

        final int pageSize = 256;
        int total = image.data.length;
        try {
            for (int address = 0; address < total; address += pageSize) {
                int length = Math.min(pageSize, total - address);
                byte[] loadAddress = {
                    (byte)((address / 2 >>> 24) & 0xFF),
                    (byte)((address / 2 >>> 16) & 0xFF),
                    (byte)((address / 2 >>> 8) & 0xFF),
                    (byte)((address / 2) & 0xFF)
                };
                checkStatus(command(LOAD_ADDRESS, loadAddress), "Задаване на адрес 0x" + Integer.toHexString(address));

                byte[] payload = new byte[10 + length];
                payload[0] = (byte)(length >>> 8);
                payload[1] = (byte)length;
                payload[2] = (byte)0xC1;
                payload[3] = 10;
                payload[4] = 0x40;
                payload[5] = 0x4C;
                payload[6] = 0x20;
                payload[7] = (byte)0xFF;
                payload[8] = (byte)0xFF;
                payload[9] = (byte)0xFF;
                System.arraycopy(image.data, address, payload, 10, length);
                checkStatus(command(PROGRAM_FLASH_ISP, payload), "Запис на страница 0x" + Integer.toHexString(address));
                progress.update(Math.min(address + length, total), total);
            }
        } finally {
            try { command(LEAVE_PROGMODE_ISP, new byte[]{0x01, 0x01}); }
            catch (Exception ignored) { /* При прекъсната връзка няма как да се изпрати командата за изход. */ }
        }
    }

    /** Опакова команда и проверява отговора и контролната сума. */
    private synchronized byte[] command(int command, byte[] parameters) throws Exception {
        int payloadLength = parameters.length + 1;
        byte[] packet = new byte[payloadLength + 6];
        packet[0] = MESSAGE_START;
        packet[1] = (byte)(sequence++ & 0xFF);
        packet[2] = (byte)(payloadLength >>> 8);
        packet[3] = (byte)payloadLength;
        packet[4] = TOKEN;
        packet[5] = (byte)command;
        System.arraycopy(parameters, 0, packet, 6, parameters.length);
        int checksum = 0;
        for (int i = 0; i < packet.length - 1; i++) checksum ^= packet[i] & 0xFF;
        packet[packet.length - 1] = (byte)checksum;
        port.write(packet, 2500);

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            int start = readByte(deadline);
            if (start != MESSAGE_START) continue;
            int seq = readByte(deadline);
            int hi = readByte(deadline);
            int lo = readByte(deadline);
            int token = readByte(deadline);
            if (seq != (packet[1] & 0xFF) || token != TOKEN) continue;
            int size = (hi << 8) | lo;
            if (size < 1 || size > 300) throw new IOException("Невалидна дължина на отговора.");
            byte[] response = new byte[size + 1];
            readExact(response, deadline);
            int xor = start ^ seq ^ hi ^ lo ^ token;
            for (byte b : response) xor ^= b & 0xFF;
            if (xor != 0) throw new IOException("Грешна контролна сума в отговора.");
            if ((response[0] & 0xFF) != ((command | 0x80) & 0xFF))
                throw new IOException("Неочакван отговор от bootloader-а.");
            return Arrays.copyOf(response, size);
        }
        throw new IOException("Изтече времето за отговор от bootloader-а.");
    }

    private int readByte(long deadline) throws IOException {
        byte[] one = new byte[1];
        readExact(one, deadline);
        return one[0] & 0xFF;
    }

    private void readExact(byte[] buffer, long deadline) throws IOException {
        int offset = 0;
        while (offset < buffer.length && System.currentTimeMillis() < deadline) {
            int count = port.read(buffer, Math.min(500, (int)Math.max(1, deadline - System.currentTimeMillis())));
            if (count > 0) offset += count;
        }
        if (offset != buffer.length) throw new IOException("USB връзката прекъсна или bootloader-ът не отговори.");
    }

    private void checkStatus(byte[] response, String operation) throws IOException {
        if (response.length < 2 || (response[1] & 0xFF) != STATUS_OK)
            throw new IOException(operation + " — bootloader статус: " +
                    (response.length > 1 ? (response[1] & 0xFF) : "липсва"));
    }
}
