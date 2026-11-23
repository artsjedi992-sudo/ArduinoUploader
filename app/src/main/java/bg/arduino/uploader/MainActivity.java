package bg.arduino.uploader;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Основен екран: свързване, избор на HEX файл и качване към UNO или Mega 2560. */
public class MainActivity extends Activity {
    private static final int PICK_HEX = 1001;
    private static final String ACTION_USB_PERMISSION = "bg.arduino.uploader.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private UsbDeviceConnection usbConnection;
    private String usbDiagnostics = "USB диагностиката не е налична.";
    private HexParser.Image firmware;
    private BoardProfile.Board selectedBoard = BoardProfile.Board.MEGA2560;
    private String firmwareName = "Няма избран файл";
    private TextView status;
    private TextView fileLabel;
    private TextView deviceLabel;
    private TextView progressLabel;
    private ProgressBar progressBar;
    private Button connectButton;
    private Button uploadButton;
    private EditText firmwareUrlInput;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            synchronized (this) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) openDevice(device);
                else setStatus("Няма разрешение за USB достъп. Разреши достъпа в диалога на Android.");
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, filter);
        setStatus("Готово. Избери UNO или Mega 2560 и свържи платката с USB OTG.");
        refreshUsbList();
    }

    /** Създава интерфейса програмно, за да няма нужда от допълнителни layout файлове. */
    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFFF2F5F8);

        TextView title = new TextView(this);
        title.setText("ArduinoUploader");
        title.setTextSize(25);
        title.setTextColor(0xFF12304A);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Качване на готов HEX към Arduino UNO / Mega • USB OTG");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFF425466);
        root.addView(subtitle);

        RadioGroup boardGroup = new RadioGroup(this);
        boardGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton unoOption = new RadioButton(this);
        unoOption.setText("Arduino UNO");
        unoOption.setId(View.generateViewId());
        RadioButton megaOption = new RadioButton(this);
        megaOption.setText("Mega 2560");
        megaOption.setId(View.generateViewId());
        boardGroup.addView(unoOption);
        boardGroup.addView(megaOption);
        megaOption.setChecked(true);
        boardGroup.setOnCheckedChangeListener((group, checkedId) -> {
            selectedBoard = checkedId == unoOption.getId() ? BoardProfile.Board.UNO : BoardProfile.Board.MEGA2560;
            setStatus("Избрана платка: " + selectedBoard.label + ". Използвай HEX, компилиран за същия модел.");
        });
        root.addView(boardGroup);

        deviceLabel = makeLabel("Платка: не е свързана");
        root.addView(deviceLabel);
        connectButton = makeButton("1. Открий / свържи платката");
        connectButton.setOnClickListener(v -> refreshUsbList());
        root.addView(connectButton);

        fileLabel = makeLabel(firmwareName);
        root.addView(fileLabel);
        Button chooseButton = makeButton("2. Избери HEX файл от телефона");
        chooseButton.setOnClickListener(v -> chooseHex());
        root.addView(chooseButton);

        firmwareUrlInput = new EditText(this);
        firmwareUrlInput.setSingleLine(true);
        firmwareUrlInput.setTextSize(14);
        firmwareUrlInput.setHint("HTTPS адрес към .hex файл (по избор)");
        firmwareUrlInput.setText("https://raw.githubusercontent.com/");
        root.addView(firmwareUrlInput);
        Button downloadButton = makeButton("Изтегли HEX от интернет / GitHub");
        downloadButton.setOnClickListener(v -> downloadHexFromUrl());
        root.addView(downloadButton);

        uploadButton = makeButton("3. Запиши фърмуера");
        uploadButton.setEnabled(false);
        uploadButton.setOnClickListener(v -> confirmAndUpload());
        root.addView(uploadButton);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(8)));

        progressLabel = makeLabel("0%");
        root.addView(progressLabel);

        status = makeLabel("");
        status.setTextColor(0xFF16324F);
        status.setPadding(0, dp(10), 0, dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView warning = new TextView(this);
        warning.setText("Важно: избери правилния модел платка и HEX за съответния микроконтролер. Не изключвай USB кабела по време на запис.");
        warning.setTextSize(12);
        warning.setTextColor(0xFF8A3B12);
        warning.setPadding(0, dp(8), 0, 0);
        root.addView(warning);
        setContentView(root);
    }

    private TextView makeLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(15);
        label.setTextColor(0xFF263746);
        label.setPadding(0, dp(10), 0, dp(6));
        return label;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    /** Показва списъка с USB устройства и иска системно разрешение при нужда. */
    private void refreshUsbList() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            deviceLabel.setText("Платка: не е открита USB–Serial платка");
            setStatus("Провери OTG адаптера, USB кабела и дали платката е захранена. Някои кабели са само за зареждане.");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();
        deviceLabel.setText("USB устройство: VID " + hex(device.getVendorId()) + " / PID " + hex(device.getProductId()));
        if (usbManager.hasPermission(device)) openDevice(device);
        else {
            Intent intent = new Intent(ACTION_USB_PERMISSION);
            intent.setPackage(getPackageName());
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, permissionIntent);
            setStatus("Изчакай системния прозорец за USB разрешение.");
        }
    }

    private String hex(int number) { return String.format("0x%04X", number); }

    /** Отваря USB сериен порт и задава типичните параметри на bootloader-а. */
    private void openDevice(UsbDevice device) {
        try {
            UsbSerialDriver chosen = null;
            for (UsbSerialDriver driver : UsbSerialProber.getDefaultProber().findAllDrivers(usbManager))
                if (driver.getDevice().getDeviceId() == device.getDeviceId()) { chosen = driver; break; }
            if (chosen == null) throw new Exception("Не е намерен USB сериен драйвер.");
            if (chosen.getPorts().isEmpty()) throw new Exception("USB устройството няма сериен порт.");
            closeUsb();
            usbConnection = usbManager.openDevice(chosen.getDevice());
            if (usbConnection == null) throw new Exception("Android не успя да отвори USB устройството.");
            serialPort = chosen.getPorts().get(0);
            serialPort.open(usbConnection);
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            // DTR се активира непосредствено преди upload за reset и готов CDC сериен канал.
            serialPort.setDTR(false);
            StringBuilder diagnostic = new StringBuilder();
            diagnostic.append("Драйвер: ").append(chosen.getClass().getName())
                    .append("; сериен портове: ").append(chosen.getPorts().size())
                    .append("; избран порт: ").append(serialPort.getPortNumber())
                    .append("; VID/PID: ").append(hex(device.getVendorId())).append("/")
                    .append(hex(device.getProductId())).append("; интерфейси: ");
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                android.hardware.usb.UsbInterface usbInterface = device.getInterface(i);
                if (i > 0) diagnostic.append(" | ");
                diagnostic.append("#").append(i)
                        .append(" class=").append(hex(usbInterface.getInterfaceClass()))
                        .append(" subclass=").append(hex(usbInterface.getInterfaceSubclass()))
                        .append(" protocol=").append(hex(usbInterface.getInterfaceProtocol()))
                        .append(" endpoints=").append(usbInterface.getEndpointCount());
                for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                    android.hardware.usb.UsbEndpoint endpoint = usbInterface.getEndpoint(j);
                    diagnostic.append(" [addr=").append(hex(endpoint.getAddress()))
                            .append(",type=").append(endpoint.getType())
                            .append(",dir=").append(endpoint.getDirection()).append("]");
                }
            }
            usbDiagnostics = diagnostic.toString();
            deviceLabel.setText("Платка: USB свързана • " + hex(device.getVendorId()) + "/" + hex(device.getProductId()));
            setStatus("USB портът е отворен при 115200 8N1. " + usbDiagnostics);
            updateButtons();
        } catch (Exception e) {
            closeUsb();
            setStatus("Грешка при свързване: " + e.getMessage());
        }
    }

    /** Отваря системния файлов браузър, без да изисква достъп до цялото хранилище. */
    private void chooseHex() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_HEX);
            setStatus("Избери .hex файла от файловия мениджър.");
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivityForResult(Intent.createChooser(fallback, "Избери HEX файл"), PICK_HEX);
                setStatus("Избери .hex файла от файловия мениджър.");
            } catch (Exception fallbackError) {
                setStatus("Не може да се отвори файловият мениджър: " + fallbackError.getMessage());
            }
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.isEmpty() ? "HEX файл" : segment;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_HEX) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            setStatus("Не е избран файл (изборът е отказан или файловият мениджър не върна URI).");
            return;
        }
        final Uri uri = data.getData();
        final String selectedName = getDisplayName(uri);
        fileLabel.setText("Файл: зарежда се — " + selectedName);
        setStatus("Файлът е избран. Проверявам Intel HEX съдържанието…");
        try {
            if (Build.VERSION.SDK_INT >= 19 && (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Exception ignored) { }
        executor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) throw new Exception("Доставчикът на файла не позволи прочитането му.");
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 1024 * 1024) throw new Exception("HEX файлът е по-голям от 1 MB.");
                    if (n > 0) out.write(buffer, 0, n);
                }
                HexParser.Image parsed = HexParser.parse(out.toByteArray());
                runOnUiThread(() -> {
                    firmware = parsed;
                    firmwareName = selectedName;
                    fileLabel.setText("Файл: " + firmwareName + "\nРазмер на образа: " + (parsed.highestAddress + 1) + " байта");
                    setStatus("HEX файлът е зареден и валидиран успешно.");
                    updateButtons();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    firmware = null;
                    firmwareName = "Няма избран валиден HEX файл";
                    fileLabel.setText("Файл: " + selectedName + " (грешка при зареждане)");
                    setStatus("Неуспешно зареждане на '" + selectedName + "': " + e.getMessage());
                    updateButtons();
                });
            }
        });
    }

    /** Изтегля HEX от директен HTTPS адрес, след което проверява Intel HEX контролните суми. */
    private void downloadHexFromUrl() {
        String address = firmwareUrlInput.getText().toString().trim();
        if (!address.startsWith("https://")) {
            setStatus("За безопасност се приемат само HTTPS адреси към суровия .hex файл.");
            return;
        }
        setStatus("Изтегляне на HEX файл…");
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(address);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "ArduinoUploader-Android");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP грешка: " + code);
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        if (out.size() + n > 1024 * 1024) throw new Exception("HEX файлът е прекалено голям.");
                        out.write(buffer, 0, n);
                    }
                    HexParser.Image parsed = HexParser.parse(out.toByteArray());
                    String name = url.getPath();
                    int slash = name.lastIndexOf('/');
                    name = slash >= 0 ? name.substring(slash + 1) : name;
                    if (name.isEmpty()) name = "firmware.hex";
                    final String finalName = name;
                    runOnUiThread(() -> {
                        firmware = parsed;
                        firmwareName = finalName;
                        fileLabel.setText("Файл: " + firmwareName + "\\nРазмер на образа: " + (parsed.highestAddress + 1) + " байта");
                        setStatus("HEX изтеглен и проверен успешно.");
                        updateButtons();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Неуспешно изтегляне: " + e.getMessage()));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    /** Изисква изрично потвърждение преди операция, която изтрива/презаписва Flash паметта. */
    private void confirmAndUpload() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Запис на фърмуер")
                .setMessage("Ще се запише " + firmwareName + " в " + selectedBoard.label + ". Не изключвай платката по време на операцията. Продължаваме ли?")
                .setNegativeButton("Отказ", (d, w) -> { })
                .setPositiveButton("Запиши", (d, w) -> startUpload())
                .show();
    }

    private void startUpload() {
        if (serialPort == null || firmware == null) {
            setStatus("Първо свържи платката и избери валиден HEX файл.");
            return;
        }
        uploadButton.setEnabled(false);
        connectButton.setEnabled(false);
        progressBar.setProgress(0);
        progressLabel.setText("0%");
        final UsbSerialPort port = serialPort;
        final HexParser.Image image = firmware;
        final BoardProfile.Board board = selectedBoard;
        executor.execute(() -> {
            try {
                // AVRDUDE arduino_open(): освобождаваме DTR, изчакваме 250 ms,
                // подаваме кратък reset импулс (100 us), освобождаваме DTR и чакаме 100 ms.
                // Някои USB-UART адаптери/Android драйвери отказват controlTransfer за DTR.
                boolean automaticReset = true;
                try {
                    // DTR/RTS reset sequence, matching the known-working Kotlin uploader:
                    // release both control lines, then assert them again before STK_GET_SYNC.
                    // DTR is the usual Uno auto-reset line; RTS is optional and is tested here
                    // because some USB-serial adapters expose it differently.
                    port.setDTR(false);
                    try { port.setRTS(false); } catch (Exception ignored) { }
                    Thread.sleep(50);
                    // Assert DTR to create the Uno auto-reset edge and keep it asserted so
                    // CDC-ACM remains ready while Optiboot initializes.
                    port.setDTR(true);
                    try { port.setRTS(true); } catch (Exception ignored) { }
                    Thread.sleep(600);
                } catch (Exception resetError) {
                    automaticReset = false;
                    runOnUiThread(() -> setStatus("USB адаптерът отказа автоматичния DTR reset ("
                            + resetError.getMessage() + "). Натисни RESET на Arduino UNO сега; "
                            + "ще опитам да се синхронизирам ръчно."));
                    // При отказан DTR оставяме време за ръчен RESET след показване на съобщението.
                    Thread.sleep(3000);
                }
                // Mega bootloader-ът стартира по-бавно; UNO следва времето на AVRDUDE.
                if (board == BoardProfile.Board.MEGA2560) Thread.sleep(1100);
                if (board == BoardProfile.Board.UNO) {
                    new Stk500v1Programmer(port).upload(image, (written, total) -> runOnUiThread(() -> {
                        int percent = total == 0 ? 0 : (int)(written * 100L / total);
                        progressBar.setProgress(percent);
                        progressLabel.setText(percent + "% (" + written + " / " + total + " байта)");
                        setStatus("Записва се фърмуерът за UNO…");
                    }));
                } else {
                    new Stk500v2Programmer(port).upload(image, (written, total) -> runOnUiThread(() -> {
                    int percent = total == 0 ? 0 : (int)(written * 100L / total);
                    progressBar.setProgress(percent);
                    progressLabel.setText(percent + "% (" + written + " / " + total + " байта)");
                    setStatus("Записва се фърмуерът за Mega 2560…");
                    }));
                }
                runOnUiThread(() -> {
                    progressBar.setProgress(100);
                    progressLabel.setText("100%");
                    setStatus("Командите за запис са изпратени успешно. Препоръчва се функционална проверка на платката.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("ГРЕШКА при запис: " + e.getMessage()
                        + "\nUSB диагностика: " + usbDiagnostics
                        + "\nНе изключвай захранването веднага; изчакай и опитай отново."));
            } finally {
                runOnUiThread(() -> {
                    connectButton.setEnabled(true);
                    updateButtons();
                });
            }
        });
    }

    private void updateButtons() {
        uploadButton.setEnabled(serialPort != null && firmware != null);
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private void closeUsb() {
        try { if (serialPort != null) serialPort.close(); } catch (Exception ignored) {}
        try { if (usbConnection != null) usbConnection.close(); } catch (Exception ignored) {}
        serialPort = null;
        usbConnection = null;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        closeUsb();
        executor.shutdownNow();
    }
}
