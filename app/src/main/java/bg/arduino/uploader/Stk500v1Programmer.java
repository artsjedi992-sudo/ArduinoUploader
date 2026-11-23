package bg.arduino.uploader;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.IOException;
import java.util.Arrays;

/** STK500v1 programmer for the Arduino Uno R3 ATmega328P bootloader. */
public final class Stk500v1Programmer {
    public interface Progress { void update(int written, int total); }

    private static final int CMD_SIGN_ON = '0';
    private static final int CMD_ENTER_PROGMODE = 'P';
    private static final int CMD_LEAVE_PROGMODE = 'Q';
    private static final int CMD_LOAD_ADDRESS = 'A';
    private static final int CMD_PROGRAM_FLASH = 'd';
    private static final int CMD_READ_FLASH = 't';
    private static final int CMD_STK_SET_DEVICE = 'B';
    private static final int CMD_STK_GET_SYNC = 'u';
    private static final int STK_OK = 0x10;
    private static final int STK_INSYNC = 0x14;
    private static final int PAGE_SIZE = 128;

    private final UsbSerialPort port;

    public Stk500v1Programmer(UsbSerialPort port) {
        this.port = port;
    }

    public void upload(HexParser.Image image, Progress progress) throws Exception {
        if (image.highestAddress >= 0x7000) {
            throw new IOException("Адресът е извън приложната Flash област на ATmega328P (bootloader защитен).");
        }
        drain();
        getSync();

        byte[] enterParameters = deviceParameters();
        command(CMD_STK_SET_DEVICE, enterParameters);
        command(CMD_ENTER_PROGMODE, null);

        int total = image.data.length;
        try {
            for (int address = 0; address < total; address += PAGE_SIZE) {
                int length = Math.min(PAGE_SIZE, total - address);
                loadAddress(address);

                byte[] page = new byte[128];
                Arrays.fill(page, (byte) 0xFF);
                System.arraycopy(image.data, address, page, 0, length);

                byte[] program = new byte[page.length + 2];
                program[0] = (byte) (page.length >>> 8);
                program[1] = (byte) page.length;
                System.arraycopy(page, 0, program, 2, page.length);
                program[page.length + 1] = 'F';
                command(CMD_PROGRAM_FLASH, program);

                loadAddress(address);
                byte[] readRequest = {(byte) 0, (byte) page.length, 'F'};
                byte[] read = command(CMD_READ_FLASH, readRequest);
                if (read.length != page.length) {
                    throw new IOException("Невалиден размер при проверка на Flash на адрес 0x"
                            + Integer.toHexString(address));
                }
                for (int i = 0; i < page.length; i++) {
                    if (read[i] != page[i]) {
                        throw new IOException("Проверката на Flash е неуспешна при адрес 0x"
                                + Integer.toHexString(address + i));
                    }
                }
                if (progress != null) progress.update(Math.min(address + length, total), total);
            }
        } finally {
            try { command(CMD_LEAVE_PROGMODE, null); }
            catch (Exception ignored) { }
        }
    }

    private byte[] deviceParameters() {
        return new byte[]{
                0x13, 0x95, 0x0F, 0x74, 0x2E, 0xFF, 0xFF,
                0xFF, 0xFF, 0xC0, 0x00, 0xD9, 0xC9, 0x32,
                0xF1, 0xA3, 0x2A, 0x20, 0x43, 0x00
        };
    }

    private void getSync() throws IOException {
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                command(CMD_STK_GET_SYNC, null);
                return;
            } catch (IOException ignored) { }
            try { Thread.sleep(100); } catch (InterruptedException e) {
                throw new IOException("Операцията е прекъсната.", e);
            }
        }
        throw new IOException("ATmega328P bootloader-ът не отговори. Провери DTR/RESET и baud 115200.");
    }

    private void loadAddress(int byteAddress) throws Exception {
        int wordAddress = byteAddress / 2;
        byte[] request = {(byte) (wordAddress & 0xFF), (byte) ((wordAddress >>> 8) & 0xFF)};
        command(CMD_LOAD_ADDRESS, request);
    }

    private void drain() throws IOException {
        byte[] buffer = new byte[256];
        try {
            for (int i = 0; i < 3; i++) {
                int count = port.read(buffer, 30);
                if (count <= 0) break;
            }
        } catch (IOException ignored) { }
    }

    private byte[] command(int command, byte[] parameters) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(0x30);
        packet.write(command);
        if (parameters != null) packet.write(parameters, 0, parameters.length);
        packet.write(0x20);

        port.write(packet.toByteArray(), 2500);
        byte[] first = readFrame(3000);
        if (first.length == 0 || first[0] != STK_INSYNC) {
            throw new IOException("Очакван STK_INSYNC от UNO bootloader-а.");
        }

        if (command == CMD_READ_FLASH) {
            byte[] data = new byte[first.length - 2];
            System.arraycopy(first, 1, data, 0, data.length);
            if (first[first.length - 1] != STK_OK) throw new IOException("Flash прочитането отказано.");
            return data;
        }

        if (first.length < 2 || first[first.length - 1] != STK_OK) {
            throw new IOException("STK500v1 статус "
                    + (first.length > 0 ? String.format("0x%02X", first[first.length - 1] & 0xFF) : "липсва"));
        }
        return Arrays.copyOfRange(first, 1, first.length - 1);
    }

    private byte[] readFrame(int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[128];
        boolean insyncSeen = false;

        while (System.currentTimeMillis() < deadline) {
            int count = port.read(buffer, 100);
            if (count > 0) {
                response.write(buffer, 0, count);
                byte[] data = response.toByteArray();
                if (!insyncSeen) {
                    for (byte value : data) if ((value & 0xFF) == STK_INSYNC) { insyncSeen = true; break; }
                }
                if (insyncSeen) {
                    for (byte value : data) {
                        if ((value & 0xFF) == STK_OK) return data;
                    }
                }
            }
        }
        throw new IOException("Няма отговор от UNO bootloader-а в рамките на 3 секунди.");
    }
}
