package bg.arduino.uploader;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * STK500v2 programmer ported from:
 * lanouvelleecole/ArduinoGPTHex
 * ArduinoUploader/BootloaderProgrammers/Protocols/STK500v2/
 *
 * Mega 2560 profile and command payloads follow that implementation.
 */
public final class Stk500v2Programmer {
    public interface Progress { void update(int written, int total); }

    private static final int MESSAGE_START = 0x1B;
    private static final int TOKEN = 0x0E;
    private static final int SIGN_ON = 0x01;
    private static final int GET_PARAMETER = 0x03;
    private static final int LOAD_ADDRESS = 0x06;
    private static final int ENTER_PROGMODE_ISP = 0x10;
    private static final int LEAVE_PROGMODE_ISP = 0x11;
    private static final int PROGRAM_FLASH_ISP = 0x13;
    private static final int READ_FLASH_ISP = 0x14;
    private static final int SPI_MULTI = 0x1D;
    private static final int STATUS_OK = 0x00;
    private static final int PAGE_SIZE = 256;

    private final UsbSerialPort port;
    private int sequence = 0;

    public Stk500v2Programmer(UsbSerialPort port) {
        this.port = port;
    }

    /** Same order as ArduinoGPTHex: sync, signature, initialize, enable, write, verify, leave. */
    public void upload(HexParser.Image image, Progress progress) throws Exception {
        byte[] signOn = command(SIGN_ON, new byte[0]);
        if (signOn.length < 11 || (signOn[1] & 0xFF) != STATUS_OK) {
            throw new IOException("STK500v2 SIGN_ON неуспешен или отговорът е непълен.");
        }
        int signatureLength = signOn[2] & 0xFF;
        if (signatureLength <= 0 || signOn.length < 3 + signatureLength) {
            throw new IOException("STK500v2 SIGN_ON върна невалиден bootloader идентификатор.");
        }
        String signature = new String(signOn, 3, signatureLength, StandardCharsets.US_ASCII);
        if (!"AVRISP_2".equals(signature)) {
            throw new IOException("Неочакван bootloader: " + signature + " (очаква се AVRISP_2).");
        }

        // ArduinoGPTHex.InitializeDevice(): GET_PARAMETER 0x90, 0x91, 0x92, 0x94.
        getParameter(0x90);
        getParameter(0x91);
        getParameter(0x92);
        getParameter(0x94);

        // AtMega2560 values from ArduinoGPTHex/Hardware/AtMega2560.java.
        byte[] enter = {
                (byte) 200, (byte) 100, (byte) 25, (byte) 32,
                (byte) 0, (byte) 0x53, (byte) 3,
                (byte) 0xAC, (byte) 0x53, (byte) 0x00, (byte) 0x00
        };
        checkResponse(command(ENTER_PROGMODE_ISP, enter), ENTER_PROGMODE_ISP, "Влизане в режим за програмиране");

        // The reference implementation executes the signature and fuse SPI reads
        // after enabling programming mode.
        spiRead(new byte[]{0x30, 0x00, 0x00, 0x00});
        spiRead(new byte[]{0x30, 0x00, 0x01, 0x00});
        spiRead(new byte[]{0x30, 0x00, 0x02, 0x00});
        spiRead(new byte[]{0x50, 0x00, 0x00, 0x00});
        spiRead(new byte[]{0x50, 0x00, 0x00, 0x00});
        spiRead(new byte[]{0x50, 0x00, 0x00, 0x00});
        spiRead(new byte[]{0x58, 0x08, 0x00, 0x00});
        spiRead(new byte[]{0x58, 0x08, 0x00, 0x00});
        spiRead(new byte[]{0x58, 0x08, 0x00, 0x00});
        spiRead(new byte[]{0x50, 0x08, 0x00, 0x00});
        spiRead(new byte[]{0x50, 0x08, 0x00, 0x00});
        spiRead(new byte[]{0x50, 0x08, 0x00, 0x00});

        int total = image.data.length;
        boolean entered = true;
        try {
            for (int address = 0; address < total; address += PAGE_SIZE) {
                int validLength = Math.min(PAGE_SIZE, total - address);

                // STK500v2 flash word address. The reference adds 0x80 to the
                // high address byte to identify Flash memory.
                int wordAddress = address >>> 1;
                byte[] loadAddress = {
                        (byte) (((wordAddress >>> 24) & 0xFF) | 0x80),
                        (byte) ((wordAddress >>> 16) & 0xFF),
                        (byte) ((wordAddress >>> 8) & 0xFF),
                        (byte) (wordAddress & 0xFF)
                };
                checkResponse(command(LOAD_ADDRESS, loadAddress), LOAD_ADDRESS,
                        "Задаване на Flash адрес 0x" + Integer.toHexString(address));

                // ArduinoGPTHex AtMega2560 FlashMemory: page 256, delay 10,
                // write bytes 40 4C, read byte 20, poll values 00 00.
                byte[] page = new byte[PAGE_SIZE];
                Arrays.fill(page, (byte) 0xFF);
                System.arraycopy(image.data, address, page, 0, validLength);

                byte[] writePayload = new byte[10 + PAGE_SIZE];
                writePayload[0] = (byte) (PAGE_SIZE >>> 8);
                writePayload[1] = (byte) PAGE_SIZE;
                writePayload[2] = (byte) 0xC1;
                writePayload[3] = (byte) 10;
                writePayload[4] = (byte) 0x40;
                writePayload[5] = (byte) 0x4C;
                writePayload[6] = (byte) 0x20;
                writePayload[7] = 0x00;
                writePayload[8] = 0x00;
                writePayload[9] = 0x00;
                System.arraycopy(page, 0, writePayload, 10, PAGE_SIZE);
                checkResponse(command(PROGRAM_FLASH_ISP, writePayload), PROGRAM_FLASH_ISP,
                        "Запис на Flash страница 0x" + Integer.toHexString(address));

                // ArduinoGPTHex verifies every programmed Flash page with READ_FLASH_ISP.
                byte[] readPayload = {
                        (byte) (PAGE_SIZE >>> 8),
                        (byte) PAGE_SIZE,
                        (byte) 0x20
                };
                byte[] readResponse = command(READ_FLASH_ISP, readPayload);
                if (readResponse.length != PAGE_SIZE + 2
                        || (readResponse[1] & 0xFF) != STATUS_OK) {
                    throw new IOException("Проверката на Flash страница 0x"
                            + Integer.toHexString(address) + " върна невалиден отговор.");
                }
                byte[] readPage = Arrays.copyOfRange(readResponse, 2, 2 + PAGE_SIZE);
                if (!Arrays.equals(page, readPage)) {
                    int mismatch = 0;
                    while (mismatch < PAGE_SIZE && page[mismatch] == readPage[mismatch]) mismatch++;
                    throw new IOException("Разлика при проверка на Flash адрес 0x"
                            + Integer.toHexString(address + mismatch) + ".");
                }

                progress.update(Math.min(address + validLength, total), total);
            }
        } finally {
            if (entered) {
                try {
                    byte[] leave = command(LEAVE_PROGMODE_ISP, new byte[]{0x01, 0x01});
                    checkResponse(leave, LEAVE_PROGMODE_ISP, "Излизане от режим за програмиране");
                } catch (Exception ignored) {
                    // Preserve the original programming/verification error.
                }
            }
        }
    }

    private int getParameter(int parameter) throws Exception {
        byte[] response = command(GET_PARAMETER, new byte[]{(byte) parameter});
        if (response.length < 3 || (response[1] & 0xFF) != STATUS_OK) {
            throw new IOException("STK500v2 GET_PARAMETER 0x"
                    + Integer.toHexString(parameter) + " неуспешен.");
        }
        return response[2] & 0xFF;
    }

    private void spiRead(byte[] spiBytes) throws Exception {
        byte[] payload = new byte[8];
        payload[0] = 4; // numTx
        payload[1] = 4; // numRx
        payload[2] = 0; // rxStartAddr
        System.arraycopy(spiBytes, 0, payload, 3, 4);
        byte[] response = command(SPI_MULTI, payload);
        checkResponse(response, SPI_MULTI, "SPI команда");
    }

    private void checkResponse(byte[] response, int command, String operation) throws IOException {
        if (response.length < 2 || (response[0] & 0xFF) != command
                || (response[1] & 0xFF) != STATUS_OK) {
            throw new IOException(operation + " — неуспешен STK500v2 отговор.");
        }
    }

    /** STK500v2 frame: sequence starts at 1, as in ArduinoGPTHex. */
    private synchronized byte[] command(int command, byte[] parameters) throws Exception {
        int bodyLength = parameters.length + 1;
        byte[] packet = new byte[bodyLength + 6];
        packet[0] = (byte) MESSAGE_START;
        if (sequence >= 255) sequence = 0;
        sequence++;
        packet[1] = (byte) sequence;
        packet[2] = (byte) (bodyLength >>> 8);
        packet[3] = (byte) bodyLength;
        packet[4] = (byte) TOKEN;
        packet[5] = (byte) command;
        System.arraycopy(parameters, 0, packet, 6, parameters.length);
        int checksum = 0;
        for (int i = 0; i < packet.length - 1; i++) checksum ^= packet[i] & 0xFF;
        packet[packet.length - 1] = (byte) checksum;
        port.write(packet, 2500);

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            int start = readByte(deadline);
            if (start != MESSAGE_START) continue;
            int seq = readByte(deadline);
            int hi = readByte(deadline);
            int lo = readByte(deadline);
            int token = readByte(deadline);
            if (seq != (packet[1] & 0xFF)) {
                throw new IOException("STK500v2 отговорът е с грешен номер на последователност.");
            }
            if (token != TOKEN) {
                throw new IOException("STK500v2 отговорът няма правилния TOKEN.");
            }
            int size = (hi << 8) | lo;
            if (size < 1 || size > 300) {
                throw new IOException("STK500v2 върна невалидна дължина: " + size);
            }

            byte[] payloadAndChecksum = new byte[size + 1];
            readExact(payloadAndChecksum, deadline);
            int xor = start ^ seq ^ hi ^ lo ^ token;
            for (byte value : payloadAndChecksum) xor ^= value & 0xFF;
            if (xor != 0) throw new IOException("Грешна STK500v2 контролна сума в отговора.");

            byte[] response = Arrays.copyOf(payloadAndChecksum, size);
            if ((response[0] & 0xFF) != (command & 0xFF)) {
                throw new IOException("STK500v2 върна отговор за друга команда.");
            }
            return response;
        }
        throw new IOException("Изтече времето за отговор от Mega bootloader-а.");
    }

    private int readByte(long deadline) throws IOException {
        byte[] one = new byte[1];
        readExact(one, deadline);
        return one[0] & 0xFF;
    }

    private void readExact(byte[] buffer, long deadline) throws IOException {
        int offset = 0;
        while (offset < buffer.length && System.currentTimeMillis() < deadline) {
            int timeout = Math.min(1000,
                    (int) Math.max(1, deadline - System.currentTimeMillis()));
            try {
                byte[] chunk = new byte[buffer.length - offset];
                int count = port.read(chunk, timeout);
                if (count > 0) {
                    int copied = Math.min(count, buffer.length - offset);
                    System.arraycopy(chunk, 0, buffer, offset, copied);
                    offset += copied;
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
        if (offset != buffer.length) {
            throw new IOException("Няма пълен отговор от Mega bootloader-а в рамките на 5 секунди.");
        }
    }
}
