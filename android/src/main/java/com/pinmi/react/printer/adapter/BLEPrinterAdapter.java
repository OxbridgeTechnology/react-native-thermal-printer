package com.pinmi.react.printer.adapter;

import static com.pinmi.react.printer.adapter.UtilsImage.getPixelsSlow;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth (RFCOMM/SPP) thermal printer adapter.
 *
 * Image printing uses ESC/POS GS v 0 (raster mode) and sends the whole
 * image as one continuous raster command with no pauses, so the print
 * head never stops mid-image (stopping leaves white lines).
 */
public class BLEPrinterAdapter implements PrinterAdapter {

    private static BLEPrinterAdapter mInstance;

    private final String LOG_TAG = "RNBLEPrinter";

    private BluetoothDevice mBluetoothDevice;
    private BluetoothSocket mBluetoothSocket;

    private ReactApplicationContext mContext;

    private final static char ESC_CHAR = 0x1B;

    private final static byte[] SET_LINE_SPACE_32 = new byte[]{
            ESC_CHAR,
            0x33,
            32
    };

    private final static byte[] LINE_FEED = new byte[]{
            0x0A
    };

    private static final byte[] CENTER_ALIGN = {
            0x1B,
            0x61,
            0x31
    };

    private BLEPrinterAdapter() {
    }

    public static BLEPrinterAdapter getInstance() {
        if (mInstance == null) {
            mInstance = new BLEPrinterAdapter();
        }

        return mInstance;
    }

    // ---------------------------------------------------------
    // INITIALIZATION
    // ---------------------------------------------------------

    @Override
    public void init(
            ReactApplicationContext reactContext,
            Callback successCallback,
            Callback errorCallback
    ) {

        this.mContext = reactContext;

        BluetoothAdapter bluetoothAdapter = getBTAdapter();

        if (bluetoothAdapter == null) {
            errorCallback.invoke("No bluetooth adapter available");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            errorCallback.invoke("bluetooth adapter is not enabled");
            return;
        }

        successCallback.invoke();
    }

    private static BluetoothAdapter getBTAdapter() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    // ---------------------------------------------------------
    // DEVICES
    // ---------------------------------------------------------

    @Override
    public List<PrinterDevice> getDeviceList(
            Callback errorCallback
    ) {

        BluetoothAdapter bluetoothAdapter = getBTAdapter();

        List<PrinterDevice> printerDevices = new ArrayList<>();

        if (bluetoothAdapter == null) {
            errorCallback.invoke("No bluetooth adapter available");
            return printerDevices;
        }

        if (!bluetoothAdapter.isEnabled()) {
            errorCallback.invoke("bluetooth is not enabled");
            return printerDevices;
        }

        Set<BluetoothDevice> pairedDevices =
                bluetoothAdapter.getBondedDevices();

        for (BluetoothDevice device : pairedDevices) {
            printerDevices.add(new BLEPrinterDevice(device));
        }

        return printerDevices;
    }

    // ---------------------------------------------------------
    // CONNECTION
    // ---------------------------------------------------------

    @Override
    public synchronized void selectDevice(
            PrinterDeviceId printerDeviceId,
            Callback successCallback,
            Callback errorCallback
    ) {

        BluetoothAdapter bluetoothAdapter = getBTAdapter();

        if (bluetoothAdapter == null) {
            errorCallback.invoke("No bluetooth adapter available");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            errorCallback.invoke("bluetooth is not enabled");
            return;
        }

        BLEPrinterDeviceId blePrinterDeviceId =
                (BLEPrinterDeviceId) printerDeviceId;

        String targetMac = blePrinterDeviceId.getInnerMacAddress();

        if (
                this.mBluetoothDevice != null
                        && this.mBluetoothSocket != null
                        && this.mBluetoothDevice.getAddress().equals(targetMac)
        ) {
            Log.v(LOG_TAG, "Already connected");

            successCallback.invoke(
                    new BLEPrinterDevice(this.mBluetoothDevice).toRNWritableMap()
            );

            return;
        }

        closeConnectionIfExists();

        Set<BluetoothDevice> pairedDevices =
                bluetoothAdapter.getBondedDevices();

        for (BluetoothDevice device : pairedDevices) {

            if (device.getAddress().equals(targetMac)) {

                try {
                    connectBluetoothDevice(device, false);

                    successCallback.invoke(
                            new BLEPrinterDevice(this.mBluetoothDevice).toRNWritableMap()
                    );

                    return;

                } catch (IOException e) {
                    Log.w(LOG_TAG, "Normal RFCOMM connection failed, retrying", e);

                    try {
                        connectBluetoothDevice(device, true);

                        successCallback.invoke(
                                new BLEPrinterDevice(this.mBluetoothDevice).toRNWritableMap()
                        );

                        return;

                    } catch (IOException er) {
                        Log.e(LOG_TAG, "Bluetooth connection failed", er);
                        errorCallback.invoke(er.getMessage());
                        return;
                    }
                }
            }
        }

        String errorText =
                "Can not find the specified printing device, "
                        + "please perform Bluetooth pairing "
                        + "in the system settings first.";

        Toast.makeText(this.mContext, errorText, Toast.LENGTH_LONG).show();

        errorCallback.invoke(errorText);
    }

    private void connectBluetoothDevice(
            BluetoothDevice device,
            Boolean retry
    ) throws IOException {

        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

        if (retry) {
            try {
                this.mBluetoothSocket =
                        (BluetoothSocket) device.getClass()
                                .getMethod("createRfcommSocket", new Class[]{int.class})
                                .invoke(device, 1);

            } catch (Exception e) {
                throw new IOException("Failed to create fallback RFCOMM socket", e);
            }

        } else {
            this.mBluetoothSocket =
                    device.createInsecureRfcommSocketToServiceRecord(uuid);

            this.mBluetoothSocket.connect();
        }

        this.mBluetoothDevice = device;
    }

    @Override
    public synchronized void closeConnectionIfExists() {

        try {
            if (this.mBluetoothSocket != null) {
                this.mBluetoothSocket.close();
            }

        } catch (IOException e) {
            Log.w(LOG_TAG, "Error closing Bluetooth socket", e);

        } finally {
            this.mBluetoothSocket = null;
            this.mBluetoothDevice = null;
        }
    }

    // ---------------------------------------------------------
    // LOW LEVEL BLUETOOTH WRITE
    // ---------------------------------------------------------

    private static final int WRITE_CHUNK_SIZE = 512;


    /**
     * Writes a (possibly large) buffer as a sequence of write() calls,
     * each capped at WRITE_CHUNK_SIZE. This is purely a transport-level
     * safety measure for RFCOMM; the printer still receives one
     * continuous stream. No sleeps here: pausing mid-image starves the
     * printer and produces white lines.
     */
    private void writeBluetooth(
            OutputStream outputStream,
            byte[] data
    ) throws IOException {

        if (data == null || data.length == 0) {
            return;
        }

        int offset = 0;

        while (offset < data.length) {

            int length = Math.min(WRITE_CHUNK_SIZE, data.length - offset);

            outputStream.write(data, offset, length);
            outputStream.flush();

            offset += length;
        }
    }

    // ---------------------------------------------------------
    // ESC/POS GS v 0 FULL RASTER BUILDER
    // ---------------------------------------------------------

    /**
     * Builds a single GS v 0 raster command containing the entire
     * image. The printer receives width/height once and then a
     * continuous bit-packed pixel stream, so the head prints in one
     * uninterrupted pass. Splitting the image into separate bands or
     * pausing between writes lets the printer run out of data
     * mid-image; the paper motor then stops and restarts, which shows
     * up as thin white lines across the print.
     */
    private byte[] buildFullRasterImage(int[][] pixels) {

        if (pixels == null || pixels.length == 0) {
            return null;
        }

        int height = pixels.length;
        int width = pixels[0].length;
        int widthBytes = (width + 7) / 8; // 1 bit per pixel, MSB first

        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream(8 + (widthBytes * height));

        // GS v 0 m xL xH yL yH
        buffer.write(0x1D); // GS
        buffer.write(0x76); // v
        buffer.write(0x30); // 0
        buffer.write(0x00); // m = normal mode, no scaling
        buffer.write(widthBytes & 0xFF);        // xL
        buffer.write((widthBytes >> 8) & 0xFF); // xH
        buffer.write(height & 0xFF);            // yL
        buffer.write((height >> 8) & 0xFF);     // yH

        for (int y = 0; y < height; y++) {
            int[] row = pixels[y];
            for (int bx = 0; bx < widthBytes; bx++) {
                byte b = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = bx * 8 + bit;
                    if (x < width && UtilsImage.shouldPrintColor(row[x])) {
                        b |= (byte) (1 << (7 - bit));
                    }
                }
                buffer.write(b);
            }
        }

        return buffer.toByteArray();
    }

    // ---------------------------------------------------------
    // RAW DATA
    // ---------------------------------------------------------

    @Override
    public synchronized void printRawData(
            String rawBase64Data,
            Callback errorCallback
    ) {

        if (this.mBluetoothSocket == null) {
            errorCallback.invoke(
                    "bluetooth connection is not built, may be you forgot to connectPrinter"
            );
            return;
        }

        try {
            byte[] bytes = Base64.decode(rawBase64Data, Base64.DEFAULT);

            OutputStream outputStream = this.mBluetoothSocket.getOutputStream();

            if (outputStream == null) {
                errorCallback.invoke("printer output stream is not available");
                return;
            }

            writeBluetooth(outputStream, bytes);

            Log.i(LOG_TAG, "Raw print completed");

        } catch (Exception e) {
            Log.e(LOG_TAG, "Raw print failed", e);
            closeConnectionIfExists();
            errorCallback.invoke("Raw print failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------
    // URL IMAGE
    // ---------------------------------------------------------

    public static Bitmap getBitmapFromURL(String src) {

        HttpURLConnection connection = null;
        InputStream input = null;

        try {
            URL url = new URL(src);

            connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();

            input = connection.getInputStream();

            return BitmapFactory.decodeStream(input);

        } catch (IOException e) {
            Log.e("RNBLEPrinter", "Failed to download bitmap", e);
            return null;

        } finally {
            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException ignored) {
            }

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---------------------------------------------------------
    // IMAGE DATA
    // ---------------------------------------------------------

    @Override
    public synchronized void printImageData(
            String imageUrl,
            int imageWidth,
            int imageHeight,
            Callback errorCallback
    ) {

        Bitmap bitmapImage = getBitmapFromURL(imageUrl);

        if (bitmapImage == null) {
            errorCallback.invoke("image not found");
            return;
        }

        printBitmap(bitmapImage, imageWidth, imageHeight, errorCallback);
    }

    // ---------------------------------------------------------
    // IMAGE BASE64
    // ---------------------------------------------------------

    @Override
    public synchronized void printImageBase64(
            final Bitmap bitmapImage,
            int imageWidth,
            int imageHeight,
            Callback errorCallback
    ) {

        if (bitmapImage == null) {
            errorCallback.invoke("image not found");
            return;
        }

        printBitmap(bitmapImage, imageWidth, imageHeight, errorCallback);
    }

    // ---------------------------------------------------------
    // PRINT BITMAP
    // ---------------------------------------------------------

    private synchronized void printBitmap(
            Bitmap bitmapImage,
            int imageWidth,
            int imageHeight,
            Callback errorCallback
    ) {

        if (this.mBluetoothSocket == null) {
            errorCallback.invoke(
                    "bluetooth connection is not built, may be you forgot to connectPrinter"
            );
            return;
        }

        try {
            Log.i(
                    LOG_TAG,
                    "Start printing bitmap: "
                            + bitmapImage.getWidth() + "x" + bitmapImage.getHeight()
            );

            int[][] pixels = getPixelsSlow(bitmapImage, imageWidth, imageHeight);

            OutputStream outputStream = this.mBluetoothSocket.getOutputStream();

            if (outputStream == null) {
                errorCallback.invoke("printer output stream is not available");
                return;
            }

            // Center alignment still applies to the raster block on
            // most ESC/POS clones.
            writeBluetooth(outputStream, CENTER_ALIGN);
            writeBluetooth(outputStream, buildFullRasterImage(pixels));

            // Restore normal line spacing, in case a text print follows
            // this image later in the same connection. Cheap no-op
            // otherwise.
            writeBluetooth(outputStream, SET_LINE_SPACE_32);
            writeBluetooth(outputStream, LINE_FEED);

            outputStream.flush();

            Log.i(LOG_TAG, "Bluetooth image print completed");

        } catch (Exception e) {
            Log.e(LOG_TAG, "Bluetooth image print failed", e);
            closeConnectionIfExists();
            errorCallback.invoke("Print image failed: " + e.getMessage());
        }
    }
}