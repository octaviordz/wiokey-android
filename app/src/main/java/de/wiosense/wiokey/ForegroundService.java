package de.wiosense.wiokey;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.greenrobot.eventbus.EventBus;

import java.util.List;

import de.wiosense.wiokey.bluetooth.BluetoothDeviceListing;
import de.wiosense.wiokey.bluetooth.HidDeviceController;
import de.wiosense.wiokey.bluetooth.HidDeviceProfile;
import de.wiosense.wiokey.utils.FirebaseManager;
import de.wiosense.wiokey.utils.UiUpdateEvent;

import static android.app.Notification.DEFAULT_SOUND;
import static android.app.Notification.DEFAULT_VIBRATE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static de.wiosense.wiokey.utils.FirebaseManager.EVENT_DEVICECONNECTED;

public class ForegroundService extends Service {

    private static final String TAG = "WioKey|ForegroundService";
    public static final String CHANNEL_ID = "ForegroundServiceChannel";

    private static final int REQ_CONNECT_TIMEOUT_MS = 8000;

    private static final Object mLock = new Object();

    @GuardedBy("mLock")
    private static final boolean mMonitor = false;

    private HidDeviceProfile hidDeviceProfile;
    private HidDeviceController hidDeviceController;
    private BluetoothDeviceListing bluetoothDeviceListing;

    private final HidDeviceController.ProfileListener profileListener = new HidDeviceController.ProfileListener() {
        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            synchronized (mLock) {
                if (mMonitor && device != null
                        && state == BluetoothProfile.STATE_CONNECTED
                        && device.getBondState() == BluetoothDevice.BOND_BONDED
                        && !bluetoothDeviceListing.isHidDevice(device)) {
                    Log.d(TAG, "New HID host connected and paired: " + device);
                    bluetoothDeviceListing.cacheHidDevice(device);
                    bluetoothDeviceListing.cacheHidDefaultDevice(device);
                    FirebaseManager.sendLogEvent(EVENT_DEVICECONNECTED,null);
                    // Make sure to refresh device display (in case is ON)
                    updateHidDefaultHostDevice();
                    updateHidAvailableDevices();
                }
            }

            // Update default HID device connection state as well
            if (bluetoothDeviceListing.isHidDefaultDevice(device)) {
                EventBus.getDefault().post(new UiUpdateEvent(
                        UiUpdateEvent.Type.UPDATE_DEFAULT_HID_DEVICE,
                        device,
                        hidDeviceProfile.getConnectionState(device)
                ));
            }
        }

        @Override
        public void onAppStatusChanged(boolean registered) {
            /*
             * If we are registered, we have an active controller and a default device
             * we proceed in try to connect with the default HID host. Since we do not
             * know if the device is in range and this may fail, we need to handle it.
             *
             * We do so with a simple timer.
             */
            Log.d(TAG, "On status changed " + registered);
            if (registered && hidDeviceController != null) {
                BluetoothDevice defaultDevice = bluetoothDeviceListing.getHidDefaultDevice();
                if (defaultDevice != null) {
                    Log.d(TAG, "Requesting to connect");
                    hidDeviceController.requestConnect(defaultDevice, REQ_CONNECT_TIMEOUT_MS);
                }
            }
            if (!registered && hidDeviceController != null) {
                hidDeviceController.unregister(getApplicationContext(), profileListener);
            }
        }

        @Override
        public void onInterruptData(BluetoothDevice device, int reportId, byte[] data, BluetoothHidDevice inputHost) {
            if (MainActivity.mTransactionManager != null && MainActivity.isOnForeground()) {
                MainActivity.mTransactionManager.handleReport(data, (rawReports) -> {
                    for (byte[] report : rawReports) {
                        inputHost.sendReport(device, reportId, report);
                    }
                });
            } else {
                Log.w(TAG, "Notification sent to user!");
                showRequestNotification();
            }
        }

        @Override
        public void onServiceStateChanged(BluetoothProfile proxy) {
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();
        EventBus.getDefault().register(this);
        setHid();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG,"Starting Foreground - " + intent);
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = null;
        try{
            pendingIntent = PendingIntent.getActivity(this,0, notificationIntent, FLAG_UPDATE_CURRENT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get pending intent");
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title_foregroundservice))
                .setContentText(getString((R.string.notification_body_foregroundservice)))
                .setSmallIcon(R.drawable.logo_small)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(1, notification);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if(hidDeviceController!=null){
            hidDeviceController.unregister(getApplicationContext(),
                    profileListener);
        }
        // Terminate the event bus for this foreground service
        EventBus.getDefault().post(UiUpdateEvent.Type.UPDATE_CONN_ANIMATION);
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void setHid(){
        hidDeviceController = HidDeviceController.getInstance();
        hidDeviceProfile = hidDeviceController.register(getApplicationContext(),
                profileListener);
        bluetoothDeviceListing = new BluetoothDeviceListing(getApplicationContext(), hidDeviceProfile);

        BluetoothDevice defaultHidDevice = bluetoothDeviceListing.getHidDefaultDevice();
        try {
            EventBus.getDefault().post(new UiUpdateEvent(
                    UiUpdateEvent.Type.UPDATE_CONN_ANIMATION,
                    defaultHidDevice,
                    hidDeviceProfile.getConnectionState(defaultHidDevice)
            ));
        } catch (NullPointerException e) {
            EventBus.getDefault().post(new UiUpdateEvent(
                    UiUpdateEvent.Type.UPDATE_CONN_ANIMATION,
                    null,
                    BluetoothProfile.STATE_DISCONNECTED)
            );
        }
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "WioKey Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        serviceChannel.setShowBadge(true);
        serviceChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private void showRequestNotification(){
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,0, intent, FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this,CHANNEL_ID)
                .setSmallIcon(R.drawable.logo_small)
                .setContentTitle(getString(R.string.notification_title_actionrequired))
                .setContentText(getString(R.string.notification_body_actionrequired))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setDefaults(DEFAULT_SOUND | DEFAULT_VIBRATE)
                .setAutoCancel(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(2,builder.build());
    }

    private void updateHidDefaultHostDevice(){
        BluetoothDevice defaultDevice = bluetoothDeviceListing.getHidDefaultDevice();
        EventBus.getDefault().post(new UiUpdateEvent(
                UiUpdateEvent.Type.UPDATE_DEFAULT_HID_DEVICE,
                defaultDevice,
                hidDeviceProfile.getConnectionState(defaultDevice)
        ));
    }

    private void updateHidAvailableDevices() {
        if (bluetoothDeviceListing != null) {
            List<BluetoothDevice> deviceList = bluetoothDeviceListing.getHidAvailableDevices();
            EventBus.getDefault().post(new UiUpdateEvent(
                    UiUpdateEvent.Type.UPDATE_HID_DEVICES,
                    deviceList
            ));
        }
    }
}
