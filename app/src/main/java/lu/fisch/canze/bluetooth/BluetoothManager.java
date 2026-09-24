/*
    CanZE
    Take a closer look at your ZE car

    Copyright (C) 2015 - The CanZE Team
    http://canze.fisch.lu

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or any
    later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/


/*
 * Helper class to manage the Bluetooth connection.
 *
 * This implementation talks to a Bluetooth Low Energy (BLE / GATT) adapter that
 * exposes a custom service with one "read" (notify) characteristic and one
 * "write" characteristic. It replaces the old Bluetooth Classic SPP (RFCOMM)
 * implementation while keeping the exact same public API (connect/disconnect/
 * write/read/available/isConnected), so the rest of the app (ELM327, CanSee, ...)
 * does not need to change: incoming notifications are buffered into a queue that
 * read()/available() drain, and write() splits the payload into MTU-sized chunks.
 */
package lu.fisch.canze.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.interfaces.BluetoothEvent;

/**
 * Created by robertfisch on 03.09.2015.
 * Converted from Bluetooth Classic SPP to Bluetooth Low Energy (GATT).
 */
public class BluetoothManager {

    /* --------------------------------
     * Sigleton stuff
     \ ------------------------------ */

    private static BluetoothManager instance = null;

    public static BluetoothManager getInstance() {
        if (instance == null)
            instance = new BluetoothManager();
        return instance;
    }

    /* --------------------------------
     * BLE service / characteristic UUIDs
     * --------------------------------
     * The adapter exposes a custom service with a notify ("read")
     * characteristic and a write characteristic.
     \ ------------------------------ */
    private static final UUID SERVICE_UUID     = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID READ_CHAR_UUID   = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE_CHAR_UUID  = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    // Standard Client Characteristic Configuration Descriptor, used to enable notifications
    private static final UUID CCCD_UUID        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /* --------------------------------
     * Attributes
     \ ------------------------------ */

    public static final int STATE_BLUETOOTH_NOT_AVAILABLE = -1;
    public static final int STATE_BLUETOOTH_ACTIVE = 1;
    public static final int STATE_BLUETOOTH_NOT_ACTIVE = 0;

    private final BluetoothAdapter bluetoothAdapter;

    private BluetoothGatt bluetoothGatt = null;
    private BluetoothGattCharacteristic readCharacteristic = null;
    private BluetoothGattCharacteristic writeCharacteristic = null;

    // true once services are discovered and notifications are enabled
    private volatile boolean connected = false;

    // incoming bytes, filled by notifications (onCharacteristicChanged), drained by read()/available()
    private final LinkedBlockingQueue<Byte> readQueue = new LinkedBlockingQueue<>();

    // negotiated outgoing payload size (ATT_MTU - 3); default is the BLE minimum
    private volatile int chunkSize = 20;

    // handshake / write serialization
    private volatile CountDownLatch readyLatch;
    private volatile CountDownLatch writeLatch;
    private static final long CONNECT_TIMEOUT_MS = 12000;
    private static final long WRITE_TIMEOUT_MS   = 2000;

    public boolean isDummyMode() {
        return dummyMode;
    }

    private boolean dummyMode = false;

    private BluetoothEvent bluetoothEvent;

    public static final int RETRIES_NONE = 0;
    public static final int RETRIES_INFINITE = -1;
    private Thread retryThread = null;
    private final String retryLock = ""; // ugly! but retryThread itself can be null

    private String connectBluetoothAddress = null;
    private boolean connectSecure;
    private int connectRetries;
    private boolean retry = true;

    private void debug(String text) {
        MainActivity.debug(this.getClass().getSimpleName() + ": " + text);
    }

    /**
     * Create a new manager
     */
    private BluetoothManager() {
        // get Bluetooth adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    /**
     * Determine the state of the Bluetooth hardware
     *
     * @return the state of the Bluetooth hardware
     */
    public int getHardwareState() {
        // Check for Bluetooth support and then check to make sure it is turned on
        // Emulator doesn't support Bluetooth and will return null

        if (dummyMode) return STATE_BLUETOOTH_ACTIVE;

        if (bluetoothAdapter == null) {
            return STATE_BLUETOOTH_NOT_AVAILABLE;
        } else {
            if (bluetoothAdapter.isEnabled()) {
                return STATE_BLUETOOTH_ACTIVE;
            } else {
                return STATE_BLUETOOTH_NOT_ACTIVE;
            }
        }
    }

    /* --------------------------------
     * GATT callback (all calls arrive on a binder thread)
     \ ------------------------------ */

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                debug("GATT connected, negotiating MTU");
                try {
                    // try to negotiate a bigger MTU for throughput; if the request
                    // can't even be issued, fall back to discovering services directly
                    if (!gatt.requestMtu(517)) {
                        gatt.discoverServices();
                    }
                } catch (SecurityException e) {
                    debug("Missing BLUETOOTH_CONNECT permission");
                    releaseConnect();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                debug("GATT disconnected (status " + status + ")");
                connected = false;
                readCharacteristic = null;
                writeCharacteristic = null;
                // unblock anything waiting on the handshake or on a write
                releaseConnect();
                if (writeLatch != null) writeLatch.countDown();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                chunkSize = mtu - 3;
                debug("MTU = " + mtu + " (chunk " + chunkSize + ")");
            }
            try {
                gatt.discoverServices();
            } catch (SecurityException e) {
                debug("Missing BLUETOOTH_CONNECT permission");
                releaseConnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                debug("Service discovery failed: " + status);
                releaseConnect();
                return;
            }
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                debug("Custom service not found: " + SERVICE_UUID);
                releaseConnect();
                return;
            }
            readCharacteristic = service.getCharacteristic(READ_CHAR_UUID);
            writeCharacteristic = service.getCharacteristic(WRITE_CHAR_UUID);
            if (readCharacteristic == null || writeCharacteristic == null) {
                debug("Read and/or write characteristic not found");
                releaseConnect();
                return;
            }

            // choose a write type that lets us serialize on onCharacteristicWrite
            if ((writeCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }

            // enable notifications on the read characteristic
            try {
                gatt.setCharacteristicNotification(readCharacteristic, true);
                BluetoothGattDescriptor cccd = readCharacteristic.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(cccd);
                    // readiness is confirmed in onDescriptorWrite
                } else {
                    // no CCCD available: consider the link ready right away
                    markReady();
                }
            } catch (SecurityException e) {
                debug("Missing BLUETOOTH_CONNECT permission");
                releaseConnect();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            debug("Notifications enabled (status " + status + ")");
            markReady();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (READ_CHAR_UUID.equals(characteristic.getUuid())) {
                byte[] value = characteristic.getValue();
                if (value != null) {
                    for (byte b : value) {
                        readQueue.offer(b);
                    }
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            // a chunk has been transmitted, release the waiting writer
            if (writeLatch != null) writeLatch.countDown();
        }
    };

    // mark the connection as ready and wake the connecting thread
    private void markReady() {
        readQueue.clear();
        connected = true;
        releaseConnect();
    }

    // wake the thread blocked inside privateConnect(), if any
    private void releaseConnect() {
        CountDownLatch latch = readyLatch;
        if (latch != null) latch.countDown();
    }

    /* --------------------------------
     * connect / disconnect
     \ ------------------------------ */

    public void connect() {
        if (dummyMode) return;

        if (connectBluetoothAddress == null)
            throw new InvalidParameterException("connect() has to be called at least once with parameters!");
        connect(connectBluetoothAddress, connectSecure, connectRetries);
    }

    public void connect(final String bluetoothAddress, final boolean secure, final int retries) {

        if (dummyMode) return;

        retry = true;
        privateConnect(bluetoothAddress, secure, retries);
    }

    private void privateConnect(final String bluetoothAddress, final boolean secure, final int retries) {
        synchronized (retryLock) {
            if (!(retryThread == null || !retryThread.isAlive())) {
                debug("BT: aborting connect (another one is in progress ...)");
                return;
            }
        }

        if (retry) {
            // remember parameters
            connectBluetoothAddress = bluetoothAddress;
            connectSecure = secure; // unused for BLE, kept for API compatibility
            connectRetries = retries;

            // only continue if we got an address
            if (bluetoothAddress != null && !bluetoothAddress.isEmpty() && getHardwareState() == STATE_BLUETOOTH_ACTIVE) {

                // make sure there is no more active connection
                debug("Closing previous GATT (if any)");
                closeGatt();

                // execute attached event
                if (bluetoothEvent != null) bluetoothEvent.onBeforeConnect();

                // set up a pointer to the remote node using it's address.
                debug("Get remote device: " + bluetoothAddress);
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(bluetoothAddress);

                // discovery is resource intensive so make sure it is stopped
                debug("Cancel discovery");
                try {
                    bluetoothAdapter.cancelDiscovery();
                } catch (SecurityException e) {
                    // ignore
                }

                // prepare for a fresh async handshake
                connected = false;
                readQueue.clear();
                readyLatch = new CountDownLatch(1);

                // open the GATT connection
                try {
                    debug("Open GATT connection");
                    Context context = MainActivity.getInstance().getApplicationContext();
                    if (Build.VERSION.SDK_INT >= 23) {
                        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                    } else {
                        bluetoothGatt = device.connectGatt(context, false, gattCallback);
                    }
                } catch (SecurityException e) {
                    debug("Missing BLUETOOTH_CONNECT permission");
                }

                // wait for the async handshake (connected + services + notifications) to finish
                if (bluetoothGatt != null) {
                    try {
                        readyLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }

                // execute attached event
                if (bluetoothEvent != null && connected)
                    bluetoothEvent.onAfterConnect();

                // stop here and return if everything went well
                if (connected) {
                    debug("Connected");
                    return;
                }
            }
            // if we reach this line, something went wrong and no connection has been established
            debug("Something went wrong");
            if (bluetoothAddress == null || bluetoothAddress.isEmpty())
                debug("No device address given");
            else if (getHardwareState() == STATE_BLUETOOTH_NOT_ACTIVE)
                debug("Bluetooth not active");

            debug("Closing GATT again ...");
            closeGatt();

            debug(retries + " tries left");
            if (retries != RETRIES_NONE) {
                // avoid thread state mismatches
                synchronized (retryLock) {
                    if (retryThread == null || !retryThread.isAlive()) {
                        if (retryThread != null) {
                            retryThread.interrupt();
                        }
                        debug("Starting new try");
                        retryThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(2 * 1000);
                                } catch (Exception e) {
                                    // ignore
                                }
                                (new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        BluetoothManager.getInstance().privateConnect(bluetoothAddress, secure, retries - 1);
                                    }
                                })).start();
                            }
                        });
                        // if STILL something goes wrong, in spite of the synchronize, throw hands in the air and do nothing
                        try {
                            retryThread.start();
                        } catch (Exception e) {
                            // do nothing
                        }
                    } else {
                        debug("Another try is still running --> abort this one");
                        debug("Alive: " + retryThread.isAlive());
                    }
                }
            }
        }
    }

    public void disconnect() {

        if (dummyMode) return;

        try {
            // execute attached event
            if (bluetoothEvent != null) bluetoothEvent.onBeforeDisconnect();

            retry = false;

            if (retryThread != null && retryThread.isAlive()) {
                debug("Waiting for retry-thread to stop ...");
                retryThread.join();
            }

            debug("Closing GATT");
            closeGatt();

            // execute attached event
            if (bluetoothEvent != null) bluetoothEvent.onAfterDisconnect();

            debug("Closed");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /* --------------------------------
     * input / output
     \ ------------------------------ */

    // write a message to the write characteristic, chunked to the negotiated MTU
    public void write(String message) {

        if (dummyMode) return;

        if (!isConnected() || writeCharacteristic == null) {
            MainActivity.debug("Write failed! Not connected ... M = " + message);
            return;
        }

        byte[] data = message.getBytes();
        int offset = 0;
        while (offset < data.length) {
            int len = Math.min(chunkSize, data.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(data, offset, chunk, 0, len);
            if (!writeChunk(chunk)) {
                MainActivity.debug("BT: Error sending chunk");
                break;
            }
            offset += len;
        }
    }

    // write a single chunk and wait (BLE allows only one outstanding GATT op at a time)
    private boolean writeChunk(byte[] chunk) {
        BluetoothGatt gatt = bluetoothGatt;
        BluetoothGattCharacteristic ch = writeCharacteristic;
        if (gatt == null || ch == null) return false;
        try {
            writeLatch = new CountDownLatch(1);
            ch.setValue(chunk);
            if (!gatt.writeCharacteristic(ch)) {
                return false;
            }
            return writeLatch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (SecurityException e) {
            MainActivity.debug("BT: missing BLUETOOTH_CONNECT permission");
            return false;
        } catch (InterruptedException e) {
            return false;
        }
    }

    public int read(byte[] buffer) throws IOException {

        if (dummyMode || !connected) return 0;

        int i = 0;
        Byte b;
        while (i < buffer.length && (b = readQueue.poll()) != null) {
            buffer[i++] = b;
        }
        return i;
    }

    public int read() throws IOException {

        if (dummyMode || !connected) return -1;

        Byte b = readQueue.poll();
        return (b == null) ? -1 : (b & 0xFF);
    }

    public int available() throws IOException {

        if (dummyMode || !connected) return 0;

        return readQueue.size();
    }

    public boolean isConnected() {

        if (dummyMode) return true;

        return connected && bluetoothGatt != null;
    }

    /* --------------------------------
     * Events
     \ ------------------------------ */

    public void setBluetoothEvent(BluetoothEvent bluetoothEvent) {

        if (dummyMode) return;

        this.bluetoothEvent = bluetoothEvent;
    }

    public void setDummyMode(boolean dummyMode) {
        this.dummyMode = dummyMode;
    }

    // close and release the GATT connection
    private synchronized void closeGatt() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception e) {
                /* do nothing */
            }
            bluetoothGatt = null;
        }
        connected = false;
        readCharacteristic = null;
        writeCharacteristic = null;
    }

}
