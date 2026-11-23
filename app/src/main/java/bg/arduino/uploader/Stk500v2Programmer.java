package bg.arduino.uploader;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.util.Arrays;

/**
 * STK500v2 uploader for the standard Arduino Mega 2560 bootloader (avrdude's
 * "wiring" programmer). Flash addresses in the Intel HEX image are byte
 * addresses; STK500v2 LOAD_ADDRESS uses word addresses.
 */
public final class Stk500v2Programmer {
    public interface Progress { void update(int verified, int total); }

    private static final int MESSAGE_START = 0x1B;
    private static final int TOKEN = 0x0E;
    private static final int SIGN_ON = 0x01;
    private static final int ENTER_PROGMODE_ISP = 0x10;
    private static final int LEAVE_PROGMODE_ISP = 0x11;
    private static final int LOAD_ADDRESS = 0x06;
    private static final int PROGRAM_FLASH_ISP = 0x13;
    private static final int READ_FLASH_ISP = 0x14;
    private static final int STATUS_OK = 0x00;
    private static final int PAGE_SIZE = 256;
    private static final int RESPONSE_TIMEOUT_MS = 5000;

    private final UsbSerialPort port;
    private int sequence;

    public Stk500v2Programmer(UsbSerialPort port) {
        this.port = port;
    }

    /** Handshakes with the bootloader, programs flash page-by-page, then reads it back. */
    public void upload(HexParser.Image image, Progress progress) throws Exception {
        byte[] signOn = command(SIGN_ON, new byte[0]);
        checkStatus(signOn, "STK500v2 sign-on");
        if (signOn.length >= 11) {
            String id = new String(signOn, 3, Math.min(8, signOn.length - 3));
            if (!id.startsWith("STK500_2")) {
                throw new IOException("Неочакван bootloader: " + id);
            }
        }

        byte[] enter = {0x0A, 0x19, 0x00, 0x00, 0x00, 0x53, 0x03,
                (byte) 0xAC, 0x53, 0x00, 0x00};
        checkStatus(command(ENTER_PROGMODE_ISP, enter), "Влизане в режим за програмиране");

        int total = image.data.length;
        try {
            for (int address = 0; address < total; address += PAGE_SIZE) {
                int validBytes = Math.min(PAGE_SIZE, total - address);
                byte[] page = new byte[PAGE_SIZE];
                Arrays.fill(page, (byte) 0xFF);
                System.arraycopy(image.data, address, page, 0, validBytes);

                loadAddress(address);
                checkStatus(command(PROGRAM_FLASH_ISP, programParameters(page)),
                        "Запис на страница 0x" + Integer.toHexString(address));

                // avrdude verifies flash after writing. Read each page back and compare it
                // before reporting progress or success to the user.
                loadAddress(address);
                byte[] read = command(READ_FLASH_ISP, readParameters(PAGE_SIZE));
                if (read.length != PAGE_SIZE + 2 || (read[1] & 0xFF) != STATUS_OK) {
                    throw new IOException("Невалиден отговор при проверка на Flash на адрес 0x"
                            + Integer.toHexString(address));
                }
                for (int i = 0; i < PAGE_SIZE; i++) {
                    if (read[i + 2] != page[i]) {
                        throw new IOException("Проверката на Flash е неуспешна при адрес 0x"
                                + Integer.toHexString(address + i) + ". Данните не съвпадат.");
                    }
                }
                if (progress != null) {
                    progress.update(Math.min(address + validBytes, total), total);
                }
            }
        } finally {
            try {
                command(LEAVE_PROGMODE_ISP, new byte[]{0x01, 0x01});
            } catch (Exception ignored) {
                // On a disconnected USB link it may not be possible to leave programming mode.
            }
        }
    }

    private void loadAddress(int byteAddress) throws Exception {
        int wordAddress = byteAddress / 2;
        byte[] parameters = {
                (byte) (wordAddress >>> 24),
                (byte) (wordAddress >>> 16),
                (byte) (wordAddress >>> 8),
                (byte) wordAddress
        };
        checkStatus(command(LOAD_ADDRESS, parameters),
                "Задаване на адрес 0x" + Integer.toHexString(byteAddress));
    }

    private byte[] programParameters(byte[] page) {
        byte[] parameters = new byte[10 + page.length];
        parameters[0] = (byte) (page.length >>> 8);
        parameters[1] = (byte) page.length;
        parameters[2] = (byte) 0xC1; // Programming mode: write page, poll for ready.
        parameters[3] = 10;          // Delay after programming.
        parameters[4] = 0x40;        // Load extended address command.
        parameters[5] = 0x4C;        // Write program-memory page command.
        parameters[6] = 0x20;        // Read program-memory command.
        parameters[7] = (byte) 0xFF;
        parameters[8] = (byte) 0xFF;
        parameters[9] = (byte) 0xFF;
        System.arraycopy(page, 0, parameters, 10, page.length);
        return parameters;
    }

    private byte[] readParameters(int length) {
        return new byte[]{
                (byte) (length >>> 8), (byte) length,
                (byte) 0x20, 10, 0x28, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF
        };
    }

    /** Encodes one STK500v2 packet and reads its matching, checksum-validated response. */
    private synchronized byte[] command(int command, byte[] parameters) throws Exception {
        int payloadLength = parameters.length + 1;
        byte[] packet = new byte[payloadLength + 6];
        packet[0] = MESSAGE_START;
        packet[1] = (byte) (sequence++ & 0xFF);
        packet[2] = (byte) (payloadLength >>> 8);
        packet[3] = (byte) payloadLength;
        packet[4] = TOKEN;
        packet[5] = (byte) command;
        System.arraycopy(parameters, 0, packet, 6, parameters.length);
        int checksum = 0;
        for (int i = 0; i < packet.length - 1; i++) checksum ^= packet[i] & 0xFF;
        packet[packet.length - 1] = (byte) checksum;
        port.write(packet, 2500);

        long deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            int start = readByte(deadline);
            if (start != MESSAGE_START) continue;
            int seq = readByte(deadline);
            int hi = readByte(deadline);
            int lo = readByte(deadline);
            int token = readByte(deadline);
            int size = (hi << 8) | lo;
            if (size < 1 || size > PAGE_SIZE + 16) {
                throw new IOException("Невалидна дължина на отговора от bootloader-а: " + size);
            }

            // Consume the whole frame even if it is stale, so the next attempt starts
            // at a frame boundary rather than in the middle of an old response.
            byte[] response = new byte[size + 1];
            readExact(response, deadline);
            int xor = start ^ seq ^ hi ^ lo ^ token;
            for (byte value : response) xor ^= value & 0xFF;
            if (xor != 0) throw new IOException("Грешна контролна сума в отговора от bootloader-а.");
            if (seq != (packet[1] & 0xFF) || token != TOKEN) continue;
            if ((response[0] & 0xFF) != (command & 0xFF)) {
                throw new IOException("Неочакван отговор от bootloader-а за команда 0x"
                        + Integer.toHexString(command));
            }
            return Arrays.copyOf(response, size);
        }
        throw new IOException("Изтече времето за отговор от bootloader-а. Провери автоматичния RESET, USB OTG кабела и bootloader-а.");
    }

    private int readByte(long deadline) throws IOException {
        byte[] one = new byte[1];
        readExact(one, deadline);
        return one[0] & 0xFF;
    }

    private void readExact(byte[] buffer, long deadline) throws IOException {
        int offset = 0;
        while (offset < buffer.length && System.currentTimeMillis() < deadline) {
            int timeout = Math.min(300, (int) Math.max(1, deadline - System.currentTimeMillis()));
            try {
                int count = port.read(Arrays.copyOfRange(buffer, offset, buffer.length), timeout);
                if (count > 0) {
                    // usb-serial-for-android reads into the supplied array; use an exact-size
                    // temporary buffer so partial reads are copied to the right offset.
                    byte[] chunk = new byte[count];
                    // The prior read used a temporary array, so perform it directly below instead.
                    // This block is replaced by readInto() to retain the bytes.
                    throw new IOException("Вътрешна грешка при четене на USB данни.");
                }
            } catch (IOException e) {
                String message = e.getMessage();
                if (message == null || !message.contains("USB get_status request failed")) throw e;
                try {
                    Thread.sleep(40);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Операцията е прекъсната.", interrupted);
                }
            }
        }
        if (offset != buffer.length) throw new IOException("Няма пълен отговор от bootloader-а в рамките на 5 секунди.");
    }

    private void checkStatus(byte[] response, String operation) throws IOException {
        if (response.length < 2 || (response[1] & 0xFF) != STATUS_OK) {
            throw new IOException(operation + " — bootloader статус: "
                    + (response.length > 1 ? (response[1] & 0xFF) : "липсва"));
        }
    }
}
