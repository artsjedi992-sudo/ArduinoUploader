package bg.arduino.uploader;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Чете Intel HEX и подготвя адресирана памет за ATmega2560. */
public final class HexParser {
    private HexParser() {}

    /** Резултатът съдържа байтовете и най-големия използван адрес. */
    public static final class Image {
        public final byte[] data;
        public final int highestAddress;
        Image(byte[] data, int highestAddress) {
            this.data = data;
            this.highestAddress = highestAddress;
        }
    }

    /** Парсира всички записи и проверява контролните им суми. */
    public static Image parse(byte[] fileBytes) throws Exception {
        String text = new String(fileBytes, StandardCharsets.US_ASCII);
        Map<Integer, Byte> memory = new TreeMap<>();
        int upperAddress = 0;
        int highest = -1;
        boolean eof = false;

        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (eof) throw new Exception("Има данни след EOF запис.");
            if (!line.startsWith(":") || line.length() < 11 || (line.length() & 1) == 0)
                throw new Exception("Невалиден Intel HEX запис: " + line);

            byte[] record = new byte[(line.length() - 1) / 2];
            for (int i = 0; i < record.length; i++) {
                int hi = Character.digit(line.charAt(1 + i * 2), 16);
                int lo = Character.digit(line.charAt(2 + i * 2), 16);
                if (hi < 0 || lo < 0) throw new Exception("Невалидна шестнадесетична цифра.");
                record[i] = (byte) ((hi << 4) | lo);
            }

            int sum = 0;
            for (byte b : record) sum = (sum + (b & 0xFF)) & 0xFF;
            if (sum != 0) throw new Exception("Грешна контролна сума в HEX файла.");
            int count = record[0] & 0xFF;
            if (record.length != count + 5) throw new Exception("Грешна дължина на HEX запис.");
            int address = ((record[1] & 0xFF) << 8) | (record[2] & 0xFF);
            int type = record[3] & 0xFF;

            if (type == 0x00) {
                long absolute = (long) upperAddress + address;
                if (absolute + count > 256L * 1024L)
                    throw new Exception("Адресът е извън Flash паметта на Mega 2560.");
                for (int i = 0; i < count; i++) {
                    int a = (int) absolute + i;
                    memory.put(a, record[4 + i]);
                    highest = Math.max(highest, a);
                }
            } else if (type == 0x01) {
                if (count != 0) throw new Exception("Невалиден EOF запис.");
                eof = true;
            } else if (type == 0x04) {
                if (count != 2) throw new Exception("Невалиден extended linear address.");
                upperAddress = (((record[4] & 0xFF) << 8) | (record[5] & 0xFF)) << 16;
            } else if (type == 0x02) {
                if (count != 2) throw new Exception("Невалиден extended segment address.");
                upperAddress = (((record[4] & 0xFF) << 8) | (record[5] & 0xFF)) << 4;
            } else if (type != 0x03 && type != 0x05) {
                throw new Exception("Неподдържан тип HEX запис: " + type);
            }
        }

        if (!eof) throw new Exception("HEX файлът няма EOF запис.");
        if (highest < 0) throw new Exception("HEX файлът не съдържа програмен код.");
        byte[] data = new byte[highest + 1];
        Arrays.fill(data, (byte) 0xFF);
        for (Map.Entry<Integer, Byte> entry : memory.entrySet())
            data[entry.getKey()] = entry.getValue();
        return new Image(data, highest);
    }
}
