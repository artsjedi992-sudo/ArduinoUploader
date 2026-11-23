package bg.arduino.uploader;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** STK500v1 programmer for the Arduino Uno R3 ATmega328P bootloader. */
public final class Stk500v1Programmer {
    public interface Progress { void update(int written, int total); }

    private static final int CMD_ENTER_PROGMODE = 'P';
    private static final int CMD_LEAVE_PROGMODE = 'Q';
    private static final int CMD_LOAD_ADDRESS = 'U';
    private static final int CMD_PROGRAM_FLASH = 'd';
    private static final int CMD_READ_FLASH = 't';
    private static final int CMD_STK_GET_SYNC = 0x30;
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
        command(CMD_ENTER_PROGMODE, null);

        int total = image.data.length;
        try {
            for (int address = 0; address < total; address += PAGE_SIZE) {
                int length = Math.min(PAGE_SIZE, total - address);
                loadAddress(address);
                byte[] page = new byte[PAGE_SIZE];
                Arrays.fill(page, (byte) 0xFF);
                System.arraycopy(image.data, address, page, 0, length);

                byte[] program = new byte[page.length + 2];
                program[0] = (byte) (page.length >>> 8);
                program[1] = (byte) page.length;
                System.arraycopy(page, 0, program, 2, page.length);
                program[program.length - 1] = 'F';
                command(CMD_PROGRAM_FLASH, program);

                loadAddress(address);
                byte[] read = command(CMD_READ_FLASH, new byte[]{0, (byte) PAGE_SIZE, 'F'});
                if (read.length != PAGE_SIZE) {
                    throw new IOException("Невалиден размер при проверка на Flash на адрес 0x"
                            + Integer.toHexString(address));
                }
                for (int i = 0; i < PAGE_SIZE; i++) {
                    if (read[i] != page[i]) {
                        throw new IOException("Проверката на Flash е неуспешна при адрес 0x"
                                + Integer.toHexString(address + i));
                    }
                }
                if (progress != null) progress.update(Math.min(address + length, total), total);
            }
        } finally {
            try { command(CMD_LEAVE_PROGMODE, null); } catch (Exception ignored) { }
        }
    }


    private void getSync() throws IOException {
        // AVRDUDE повтаря STK_GET_SYNC; по-дългият прозорец позволява и ръчен RESET,
        // когато Android USB-UART драйверът не поддържа DTR controlTransfer.
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                command(CMD_STK_GET_SYNC, null);
                return;
            } catch (IOException ignored) { }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Операцията е прекъсната.", e);
            }
        }
        throw new IOException("UNO bootloader-ът не отговори след 20 опита. Провери RESET, USB кабела и baud 115200.");
    }

    private void loadAddress(int byteAddress) throws Exception {
        int wordAddress = byteAddress / 2;
        command(CMD_LOAD_ADDRESS, new byte[]{(byte) wordAddress, (byte) (wordAddress >>> 8)});
    }

    private void drain() throws IOException {
        byte[] buffer = new byte[256];
        try {
            for (int i = 0; i < 3; i++) if (port.read(buffer, 30) <= 0) break;
        } catch (IOException ignored) { }
    }

    private byte[] command(int command, byte[] parameters) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(command);
        if (parameters != null) packet.write(parameters, 0, parameters.length);
        packet.write(0x20);
        port.write(packet.toByteArray(), 2500);

        int expectedData = command == CMD_READ_FLASH ? PAGE_SIZE : 0;
        byte[] response = readExact(expectedData + 2, command == CMD_STK_GET_SYNC ? 350 : 3000);
        if ((response[0] & 0xFF) != STK_INSYNC || (response[response.length - 1] & 0xFF) != STK_OK) {
            throw new IOException("Неочакван STK500v1 отговор от UNO bootloader-а.");
        }
        return Arrays.copyOfRange(response, 1, response.length - 1);
    }

    private byte[] readExact(int length, int timeoutMs) throws IOException {
        byte[] response = new byte[length];
        int offset = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (offset < length && System.currentTimeMillis() < deadline) {
            byte[] chunk = new byte[length - offset];
            int count = port.read(chunk, Math.min(250, (int) Math.max(1, deadline - System.currentTimeMillis())));
            if (count > 0) {
                System.arraycopy(chunk, 0, response, offset, Math.min(count, length - offset));
                offset += count;
            }
        }
        if (offset != length) throw new IOException("Няма пълен отговор от UNO bootloader-а.");
        return response;
    }
}
