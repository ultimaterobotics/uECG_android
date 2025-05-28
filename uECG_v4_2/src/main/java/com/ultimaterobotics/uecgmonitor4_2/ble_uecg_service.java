package com.ultimaterobotics.uecgmonitor4_2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import static android.app.Activity.RESULT_OK;
import static android.bluetooth.BluetoothAdapter.STATE_CONNECTED;
import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH;
import static android.bluetooth.BluetoothGatt.GATT_FAILURE;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
import static android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
import static android.support.v4.app.ActivityCompat.startActivityForResult;

public class ble_uecg_service extends Service {

    public static final int UECG_ALIVE_INDICATOR = 10;

    public static final int UECG_PING_REQUEST = 100;
    public static final int UECG_PING_RESPONSE = 101;

    public static final int UECG_CLOSE_REQUEST = 110;
    public static final int UECG_CLOSE_RESPONSE = 111;

    public static final int UECG_STATE_ARRAY_REQUEST = 120;
    public static final int UECG_STATE_ARRAY_RESPONSE = 121;

    public static final int UECG_SAVE_START_REQUEST = 130;
    public static final int UECG_SAVE_STOP_REQUEST = 131;

    public static final int UECG_PDF_SAVE_START_REQUEST = 135;
    public static final int UECG_PDF_SAVE_STOP_REQUEST = 136;

    public static final int UECG_CONNECT_UPDATE = 140;

    public static final int UECG_MARK_DATA_REQUEST = 201;
    public static final int UECG_MAKE_SNAPSHOT_REQUEST = 210;

    public static final int UECG_FW_UPLOAD_REQUEST = 1001;
    public static final int UECG_FW_UPLOAD_RESPONSE = 1002;

    private ble_uecg_service uecg_service;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothManager bluetoothManager;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    int bt_inited = -1;
    int first_run = 1;
    int had_err = 0;
    int had_data = 0;
    private static final int REQUEST_ENABLE_BT = 1;
    private long last_scan_restart = 0;

    private BluetoothLeScanner mLEScanner;
    private ScanSettings settings;
    private List<ScanFilter> filters;

    int firmware_len = 0;
    byte[] firmware_data;
    boolean fw_upload_requested = false;
    int fw_upload_progress = -1;

    String dev_mac = "";
    int ver_4_plus = 0;
    private Timer timer;
    Timer tim_conn;

    private boolean stop_requested = false;

    void stopScanning() {
        if(mLEScanner != null && mScanCallback != null)
            mLEScanner.stopScan(mScanCallback);
//        mScanCallback = null;
    }
    void init_bluetooth() {
        // Initializes Bluetooth adapter.
        bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
//        mBluetoothAdapter.disable();
        // Code here executes on main thread after user presses button
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            if(bt_inited == -1) {
                bt_inited = 0;
                Log.e("uECG", "ble not enabled");
                if(first_run == 1) {
                    Intent btIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    btIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getApplicationContext().startActivity(btIntent);
                    first_run = 0;
                }
                else
                {
                    if(mBluetoothAdapter != null)
                        mBluetoothAdapter.enable();
                }
//                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
//                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }
        else {

            mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
            bt_inited = 1;
            Log.e("uECG", "ble enabled");
        }
    };
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Check which request we're responding to
        if (requestCode == REQUEST_ENABLE_BT) {
            // Make sure the request was successful
            if (resultCode == RESULT_OK) {
                mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
                bt_inited = 1;
            }
        }
    };
    ECG_processor ecg_processor = new ECG_processor();
    RR_processor rr_processor = new RR_processor();
    uecg_ble_parser uecg_parser = new uecg_ble_parser();
    BPM_processor bpm_processor = new BPM_processor();

    float[] save_values = new float[500];
    long[] save_uid = new long[500];
    int[] save_valid = new int[500];
    int max_save_len = 500;

    int prev_pack_id = 0;
    int batt = 0;
    int draw_skip = 0;
    int[] bpm_chart = new int[100];
    int bpm_length = 100;
    long last_bpm_add = 0;

    float lost_points_avg = 0;
    float total_points_avg = 0;

    int total_packs = 0;

    int dev_BPM = 0;
    int dev_SDRR = 0;
    int dev_RMSSD = 0;
    float calc_SDNN = 0;
    int dev_skin = 0;
    float dev_ax = 0;
    float dev_ay = 0;
    float dev_az = 0;
    float dev_wx = 0;
    float dev_wy = 0;
    float dev_wz = 0;
    float dev_T = 36;
    int dev_steps = 0;
    int dev_gg = 0;
    int dev_lastRR_id = -1;

    int rec_uid = 0;
    int unsaved_cnt = 0;

    int version_id = -1;

    int[] pNN_short = new int[32];
    int bins_count = 1;

    File log_file = null;
    File log_file_ecg = null;
    FileOutputStream file_out_stream;
    FileOutputStream file_out_stream_ecg;

    PdfDocument pdf_save;
    PdfDocument.Page pdf_cur_page;
    int pdf_save_on = 0;
    long pdf_save_page_time = 0;
    int pdf_page_num = 0;

    int save_on = 0;
    int mark_data = 0;
    int mark_data_rr = 0;
    long mark_time_last = 0;
    int mark_last = 0;
    long save_start_time = 0;
    int log_file_just_created = 0;

    int had_new_RR = 0;

    int gatt_connection_type = 0;
    int supervision_timer_working = 0;

    void pdf_save_process(int write_all_available)
    {
        if(pdf_save_on < 1) return;
        long ms = System.currentTimeMillis();
        if(write_all_available == 0)
            if(ms - pdf_save_page_time < 60000) return;
        if(write_all_available > 0)
            if(ms - pdf_save_page_time < 2000) return; //no sense to create a page if no meaningful amount of data is available
        int period_s = 60;
        if(write_all_available > 0)
        {
            period_s = (int)(ms - pdf_save_page_time);
            period_s /= 1000;
        }
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(1000, 2000, pdf_page_num).create();
        pdf_page_num++;
        pdf_cur_page = pdf_save.startPage(pageInfo);
        int put_mark = 0;
        if(mark_time_last > pdf_save_page_time)
        {
            put_mark = mark_last;
        }
        ecg_processor.draw_spanshot(pdf_cur_page.getCanvas(), 60, period_s, 1, rr_processor.get_BPM_t(60), (int)rr_processor.RMSSD_5m, rr_processor.get_min_RR_t(60), rr_processor.get_max_RR_t(60), put_mark);
        pdf_save.finishPage(pdf_cur_page);
        pdf_save_page_time = ms;
    }
    void file_save_ecg()
    {
        if(unsaved_cnt > 250) //~once per 2 seconds
        {
            if(save_on == 1) {
                String ecg_buf = "";
                int save_cnt = unsaved_cnt;
                unsaved_cnt = 0;
                if (save_cnt > max_save_len - 2) save_cnt = max_save_len - 2;
                save_cnt = ecg_processor.get_last_data_count(save_cnt, save_values, save_uid, save_valid);

                for (int n = 0; n < save_cnt; n++) {
                    ecg_buf += save_uid[n] + ";" + save_values[n] + ";" + save_valid[n] + "\n";
                }
                try {
                    file_out_stream_ecg = new FileOutputStream(log_file_ecg, true);

                    try {
                        file_out_stream_ecg.write(ecg_buf.getBytes());
                    } finally {
                        file_out_stream_ecg.close();
                    }
                } catch (IOException ex) {
                    Log.e("uECG service", "file writing 2nd exception: " + ex.getMessage());
//                                ;
//                                Log.e(ex.getMessage());
                }
            }
        }
    }
    void file_save_rr(int RR_cur, int RR_prev)
    {
        if(save_on == 1)
        {
            String out_buf = "";
            if(log_file_just_created > 0)
            {
                out_buf = "timestamp;current_RR;previous_RR;skin_resistance;BPM;RMSSD_5m;steps;accel_x;accel_y;accel_z;mark (if marked)\n";
                log_file_just_created = 0;
            }
            long tm = System.currentTimeMillis();
            out_buf += tm + ";" + RR_cur + ";" + RR_prev + ";" + dev_skin + ";" + dev_BPM + ";" + rr_processor.RMSSD_5m + ";" + dev_steps + ";" + dev_ax + ";" + dev_ay + ";" + dev_az;
            if(mark_data_rr > 0) {
                out_buf += ";mark " + mark_data_rr;
                mark_data_rr = 0;
            }
            out_buf += "\n";

            try {
                file_out_stream = new FileOutputStream(log_file, true);

                try {
                    file_out_stream.write(out_buf.getBytes());
                } finally {
                    file_out_stream.close();
                }
            }
            catch(IOException ex)
            {
                Log.e("uECG service", "file writing exception: " + ex.getMessage());
//                                ;
//                                Log.e(ex.getMessage());
            }
        }
    }

    void parse_chr_ecg_data(byte[] data_b)
    {
        int[] ecg_data = new int[40];
        int[] ecg_values = new int[40];
        for (int n = 0; n < data_b.length; n++) {
            ecg_data[n] = data_b[n];
            if (ecg_data[n] < 0)
                ecg_data[n] = 256 + ecg_data[n];
        }
        int pack_type = ecg_data[0];
        if(pack_type == 1) //ecg data
        {
            int data_id = ecg_data[1]*256 + ecg_data[2];
            int scale_code = ecg_data[3];
            int scale = scale_code;
            if(scale_code > 100) scale = 100 + (scale_code - 100)*4;
            ecg_values[0] = ecg_data[4]*256 + ecg_data[5];
            int max_dv = 0;
            int max_d2v = 0;
            if(ecg_values[0] > 32767)
                ecg_values[0] = -65536 + ecg_values[0];
            for (int n = 0; n < 13; n++) {
                ecg_values[n+1] = ecg_values[n] + (ecg_data[6+n]-128)*scale;
                int dd = ecg_values[n+1] - ecg_values[n];
                if(dd < 0) dd = -dd;
                if(dd > max_dv) max_dv = dd;
                if(n > 1)
                {
                    int d2 = ecg_values[n]*2 - ecg_values[n+1] - ecg_values[n-1];
                    if(d2 < 0) d2 = -d2;
                    if(d2 > max_d2v) max_d2v = d2;
                }
            }

            if(max_dv > 4000) return; //not a normal ecg data, ignore
            if(max_d2v > 4000) return;
            int bpm_momentary = ecg_values[19];
            int d_id = data_id - prev_pack_id;
            prev_pack_id = data_id;
            if(d_id < 0) d_id += 256*256;
            if(d_id < 0) d_id = 0;
            if(d_id > 300) d_id = 0;
            Log.e("uECG", "ecg data, id: " + data_id + " d_id " + d_id);

            if(d_id == 0)
                return;

            total_packs++;
            unsaved_cnt += d_id;
            ecg_processor.push_data(d_id, ecg_values, 9, mark_data);
            if(d_id > 0) mark_data = 0;

            Intent intent = new Intent("uECG_ECG");
            Bundle b = new Bundle();

            b.putInt("pack_id", data_id);
            b.putInt("RR_count", 8);
            b.putIntArray("RR", ecg_values);
            b.putInt("dev_BPM_momentary", bpm_momentary);

            if(log_file != null) {
                b.putString("file_path", log_file.getAbsolutePath());
                b.putBoolean("file_state", log_file.canWrite());
            }

            intent.putExtra("BLE_data_ecg", b);

            intent.addCategory(Intent.CATEGORY_DEFAULT);
            uecg_service.sendBroadcast(intent);

            file_save_ecg();
            pdf_save_process(0);
        }
        if(pack_type == 2) //imu+rr data
        {
            int ax = 0, ay = 0, az = 0;
            int wx = 0, wy = 0, wz = 0;
            int pp = 1;
            ax = ecg_data[pp++]<<4;
            ax += ecg_data[pp]>>4;
            ay = (ecg_data[pp++]&0xF)<<8;
            ay += ecg_data[pp++];
            az = ecg_data[pp++]<<4;
            az += ecg_data[pp]>>4;
            wx = (ecg_data[pp++]&0xF)<<8;
            wx += ecg_data[pp++];
            wy = ecg_data[pp++]<<4;
            wy += ecg_data[pp]>>4;
            wz = (ecg_data[pp++]&0xF)<<8;
            wz += ecg_data[pp++];
            ax -= 2048;
            ay -= 2048;
            az -= 2048;
            wx -= 2048;
            wy -= 2048;
            wz -= 2048;
            Log.e("uECG service", "acc: " + ax + " " + ay + " " + az);
            float acc_coeff = 9.81f*(float)(4.0 / 4096.0);
            float gyro_coeff = (float)(500.0*2*3.1415926/360.0/4096.0); //500 dps int16 scale to rad/s
            dev_ax = ax*acc_coeff;
            dev_ay = ay*acc_coeff;
            dev_az = az*acc_coeff;
            dev_wx = wx*gyro_coeff;
            dev_wy = wy*gyro_coeff;
            dev_wz = wz*gyro_coeff;
            float T = ecg_data[pp++];
            dev_T = 20 + T/10;
            dev_steps = ecg_data[pp]*256 + ecg_data[pp+1];
            pp += 2;
            int rr_id = ecg_data[pp++];
            int rr_cur, rr_prev;
            rr_cur = ecg_data[pp++]<<4;
            rr_cur += ecg_data[pp]>>4;
            rr_prev = (ecg_data[pp++]&0xF)<<8;
            rr_prev += ecg_data[pp++];
            dev_BPM = ecg_data[pp++];
            dev_skin = ecg_data[pp]*256 + ecg_data[pp+1];

            Intent intent = new Intent("uECG_IMU_RR");
            Bundle b = new Bundle();

            if(rr_id != dev_lastRR_id) {
                rr_processor.push_data(rr_cur, rr_prev);
                dev_lastRR_id = rr_id;
                b.putInt("has_RR", 1);
                b.putInt("RR_cur", rr_cur);
                b.putInt("RR_prev", rr_prev);
                b.putFloat("RR_rmssd5m", rr_processor.RMSSD_5m);
                file_save_rr(rr_cur, rr_prev);
            }
            b.putFloat("dev_ax", dev_ax);
            b.putFloat("dev_ay", dev_ay);
            b.putFloat("dev_az", dev_az);
            b.putFloat("dev_wx", dev_wx);
            b.putFloat("dev_wy", dev_wy);
            b.putFloat("dev_wz", dev_wz);
            b.putFloat("dev_T", dev_T);
            b.putInt("dev_steps", dev_steps);
            b.putInt("dev_skin", dev_skin);
            b.putInt("dev_BPM", dev_BPM);

            intent.putExtra("BLE_data_imu_rr", b);

            intent.addCategory(Intent.CATEGORY_DEFAULT);
            sendBroadcast(intent);

            bpm_processor.push_data(dev_BPM, dev_steps, dev_skin, rr_processor.get_hrv_parameter(), dev_ax, dev_ay, dev_az, dev_T);
        }
        if(pack_type == 3) //hrv data
        {
            int pp = 1;
            bins_count = 15;
            for(int n = 0; n < 15; n++)
                pNN_short[n] = ecg_data[pp++];
            dev_SDRR = ecg_data[pp++]<<4;
            dev_SDRR += ecg_data[pp]>>4;
            dev_RMSSD = (ecg_data[pp++]&0xF)<<8;
            dev_RMSSD += ecg_data[pp++];
            batt = ecg_data[pp++];
            Intent intent = new Intent("uECG_HRV");
            Bundle b = new Bundle();
            b.putInt("batt", batt);
            b.putInt("dev_SDRR", dev_SDRR);
            b.putInt("dev_RMSSD", dev_RMSSD);
            b.putInt("dev_pNN_bins", bins_count);
            b.putIntArray("pNNs", pNN_short);

            intent.putExtra("BLE_data_hrv", b);

            intent.addCategory(Intent.CATEGORY_DEFAULT);
            sendBroadcast(intent);
        }
        return;
    }

    int pending_char_on = 0;
    int cur_gatt_state = -1;
    BluetoothGattCharacteristic hrv_chr;
    BluetoothGattCharacteristic ecg_chr;
    BluetoothGattCharacteristic rr_chr;
    BluetoothGattCharacteristic imu_chr;
    BluetoothGattCharacteristic fw_chr = null;
    BluetoothGattDescriptor hrv_chr_desc;
    BluetoothGattDescriptor ecg_chr_desc;
    BluetoothGattDescriptor rr_chr_desc;
    BluetoothGattDescriptor imu_chr_desc;
    BluetoothGattDescriptor fw_chr_desc;
    long pending_char_ms = 0;
    private class MyGattCallback extends BluetoothGattCallback {
        Context ctx;
        void set_context(Context c) {
            ctx = c;
        };
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status)
        {
            Log.e("uECG", "service discovery status: " + status);
            gatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH);
            if(status == GATT_SUCCESS)
            {
                for(int s = 0; s < gatt.getServices().size(); s++)
                    Log.e("uECG", "services: " + gatt.getServices().get(s).getUuid().toString());

                gatt_connection_type = 1;
                if(gatt == null) {
                    Intent intent = new Intent("uECG_SIGNAL");
                    intent.putExtra("signal", ble_uecg_service.UECG_CONNECT_UPDATE);
                    Bundle b = new Bundle();
                    b.putInt("type", 2);
                    b.putString("message", "Service discovery failed"); //scan event
                    intent.putExtra("conn_state", b);
                    intent.addCategory(Intent.CATEGORY_DEFAULT);
                    sendBroadcast(intent);

                    Log.e("uECG", "gatt null");
                    return;
                }

                if(fw_upload_requested)
                {
                    BluetoothGattService fw_svc = gatt.getService( UUID.fromString("035A265E-3CA8-26EA-39C0-3CA36AE2F200"));
                    if(fw_svc== null) {
                        Log.e("uECG", "fw_svc null");
                        return;
                    }

                    fw_chr = fw_svc.getCharacteristic(UUID.fromString("772ACE23-4284-A634-8AE0-44AB38E1AC00"));
                    if(fw_chr== null) {
                        Log.e("uECG", "fw_chr null");
                        return;
                    }
                    fw_chr_desc = fw_chr.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"));
                    if(fw_chr_desc== null) {
                        Log.e("uECG", "fw_chr_desc null");
                        return;
                    }
                    Log.e("uECG", "turn on notif");

                    fw_chr_desc.setValue(ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(fw_chr_desc);
                    gatt.setCharacteristicNotification(fw_chr, true);

                    pending_char_on = 1;
                    pending_char_ms = System.currentTimeMillis();
                    return;
                }

                Intent intent = new Intent("uECG_SIGNAL");
                intent.putExtra("signal", ble_uecg_service.UECG_CONNECT_UPDATE);
                Bundle b = new Bundle();
                b.putInt("type", 2);
                b.putString("message", "Service discovery in progress..."); //scan event
                intent.putExtra("conn_state", b);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                sendBroadcast(intent);

                BluetoothGattService hr_svc = gatt.getService( UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB"));
                if(hr_svc== null) {
                    Log.e("uECG", "hr_svc null");
                    return;
                }
                BluetoothGattCharacteristic hr_chr = hr_svc.getCharacteristic(UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB"));
                if(hr_chr== null) {
                    Log.e("uECG", "hr_chr null");
                    return;
                }
                BluetoothGattDescriptor hr_chr_desc = hr_chr.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"));
                if(hr_chr_desc== null) {
                    Log.e("uECG", "hr_chr_desc null");
                    return;
                }
                Log.e("uECG", "turn on notif");

                BluetoothGattService ecg_svc = gatt.getService( UUID.fromString("93375900-F229-8B49-B397-44B5899B8600"));
                if(ecg_svc== null) {
                    Log.e("uECG", "ecg_svc null");
                    return;
                }
                ecg_chr = ecg_svc.getCharacteristic(UUID.fromString("FC7A850D-C1A5-F61F-0DA7-9995621FBD00"));
                if(ecg_chr== null) {
                    Log.e("uECG", "ecg_chr null");
                    return;
                }
                ecg_chr_desc = ecg_chr.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"));

//                gatt.requestMtu(80);

                ecg_chr_desc.setValue(ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(ecg_chr_desc);
                gatt.setCharacteristicNotification(ecg_chr, true);

//                hr_chr_desc.setValue(ENABLE_NOTIFICATION_VALUE);
//                gatt.writeDescriptor(hr_chr_desc);
//                gatt.setCharacteristicNotification(hr_chr, true);

                intent = new Intent("uECG_SIGNAL");
                intent.putExtra("signal", ble_uecg_service.UECG_CONNECT_UPDATE);
                b = new Bundle();
                b.putInt("type", 2);
                b.putString("message", "BLE discovery completed"); //scan event
                intent.putExtra("conn_state", b);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                sendBroadcast(intent);

                pending_char_on = 1;
            }
        };
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
        {
            Log.e("uECG", "service connection changed: err " + status + " st " + newState);
            cur_gatt_state = newState;

            Intent intent = new Intent("uECG_SIGNAL");
            intent.putExtra("signal", ble_uecg_service.UECG_CONNECT_UPDATE);
            Bundle b = new Bundle();
            String status_message;
            String state_message;
            status_message = " " + status + " ";
            state_message = " " + newState + " ";
            if(status == BluetoothGatt.GATT_SUCCESS) status_message = " GATT OK ";
            if(status == BluetoothGatt.GATT_FAILURE) status_message = " GATT FAIL ";
            if(status == BluetoothGatt.GATT_CONNECTION_CONGESTED) status_message = " GATT CONGESTED ";
            if(status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION) status_message = " GATT NOAUTH ";
            if(status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION) status_message = " GATT NOENCR ";
            if(status == BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH) status_message = " GATT ATTRLEN ";
            if(status == BluetoothGatt.GATT_INVALID_OFFSET) status_message = " GATT INVOFFS ";
            if(status == BluetoothGatt.GATT_READ_NOT_PERMITTED) status_message = " GATT NOREAD ";
            if(status == BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED) status_message = " GATT REQ NOSUPP ";
            if(status == BluetoothGatt.GATT_WRITE_NOT_PERMITTED) status_message = " GATT NOWRITE ";
            if(status == 133) status_message = " GATT GENERR ";

            if(newState == BluetoothProfile.STATE_CONNECTING) state_message = " connecting";
            if(newState == BluetoothProfile.STATE_CONNECTED) state_message = " connected";
            if(newState == BluetoothProfile.STATE_DISCONNECTING) state_message = " disconnecting";
            if(newState == BluetoothProfile.STATE_DISCONNECTED) state_message = " disconnected";
            b.putInt("type", 3);
            b.putString("message", "BLE state:" + status_message + "/" + state_message);
            intent.putExtra("conn_state", b);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            sendBroadcast(intent);

            if(status == 133) //typical error
            {
                gatt_connection_type = 0;
                mBluetoothAdapter.disable();
                bt_restart_issued = 1;
//                restart_scan_bluetooth();
            }
            if(status == BluetoothGatt.GATT_SUCCESS)
            {
                last_conn_data_tm = System.currentTimeMillis();
                if(newState == BluetoothProfile.STATE_CONNECTED)
                {
                    gatt.discoverServices();
                    in_conn_restart = 0;
                }
                if(newState == BluetoothProfile.STATE_CONNECTING)
                {
                    in_conn_restart = 1;
                }
                if(newState == BluetoothProfile.STATE_DISCONNECTING)
                {
                    in_conn_restart = 1;
                    connect_started = 0;
                }
                if(newState == BluetoothProfile.STATE_DISCONNECTED)
                {
                    gatt_proc_started = 0;
                    in_conn_restart = 0;
                    connect_started = 0;
                }
            }
            else
                last_conn_data_tm = System.currentTimeMillis();
        };
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic chr) {
            last_conn_data_tm = System.currentTimeMillis();
            long uuid_l = chr.getUuid().getLeastSignificantBits();
            int uuid_s = (int)((uuid_l>>8)&0xFFFF);
            Log.e("uECG", "char changed: " + uuid_s);
            byte[] data_b = chr.getValue();
            if(fw_upload_requested && uuid_s == 0xE1AC)
            {
                int opcode = data_b[0];
                if(opcode < 0) opcode += 256;
                byte pack_id = data_b[1];
                Log.e("uECG char", " opcode " + opcode + " pid " + pack_id);
                uecg_gatt = gatt;
                fw_upload_process(opcode, pack_id);
                last_conn_data_tm = System.currentTimeMillis();
            }
            if(uuid_s == 0x1FBD) //ecg data
            {
                parse_chr_ecg_data(data_b);
            }
            if(uuid_s == 0xCA90) //settings
            {
                ;//pending
            }
        };
    };
    int berr_ok = 11;
    int berr_wrongcode = 100;
    int berr_toolong = 101;
    int berr_notstarted = 102;
    int berr_wrongpack_ahead = 103;
    int berr_wrongpack_behind = 104;
    int berr_wrongpack_duplicate = 105;
    long fw_upload_time = 0;
    int fw_upload_state = 0;
    int fw_written_words = 0;
    byte[] fw_upload_send_val = new byte[17];
    int fw_upload_send_valid = 0;
    byte fw_upload_pack_id = 100;
    byte[] fw_upload_start_code = {(byte)0x10, (byte)0xFC, (byte)0xA3, (byte)0x05, (byte)0xC0, (byte)0xDE, (byte)0x11, (byte)0xAA};
    void fw_upload_process(int opcode, byte pack_id)
    {
        if((opcode == berr_ok || opcode == berr_wrongpack_behind || opcode == berr_wrongpack_duplicate) && pack_id == fw_upload_pack_id)
        {
            Log.e("uECG fw upload ", "opcode " + opcode + " pack_id " + pack_id);
            if(fw_written_words*4 >= firmware_len)
            {
                Log.e("uECG fw upload ", "completed!");
                fw_upload_progress = 1000;
                fw_upload_tim.cancel();
                fw_upload_requested = false;
                uecg_gatt.disconnect();
                dev_mac = "";
                tim_conn.cancel();
                supervision_timer_working = 0;
                restart_scan_bluetooth();
                return;
            }
            if(fw_upload_state == 0)
            {
                Log.e("uECG fw upload ", "starting, reset words cnt ");
                fw_upload_state = 1;
                fw_written_words = 0;
            }
            fw_upload_pack_id++;
            fw_upload_send_val[0] = fw_upload_pack_id;
            if(firmware_data != null)
                Log.e("uECG fw upload ", "firmware_data length: " + firmware_data.length);
            else
                Log.e("uECG fw upload ", "firmware_data null!");
            for(int x = 0; x < 16; x++)
                fw_upload_send_val[1+x] = firmware_data[fw_written_words*4 + x];
            fw_written_words += 4;
            fw_upload_send_valid = 1;
            Log.e("uECG fw upload ", "sent pack id " + fw_upload_pack_id + " progress " + fw_upload_progress);
            fw_upload_progress = fw_written_words * 4 * 100 / firmware_len;
        }
        else
        {
            if(fw_upload_state > 0) //resend
            {
                if(opcode == berr_wrongpack_ahead)
                {
                    fw_upload_pack_id--;
                    fw_written_words -= 4;
                    fw_upload_send_val[0] = fw_upload_pack_id;
                    for(int x = 0; x < 16; x++)
                        fw_upload_send_val[1+x] = firmware_data[fw_written_words*4 + x];
                    fw_upload_send_valid = 1;
                }
                else {
                    Log.e("uECG fw upload", "resend pack id " + fw_upload_pack_id);
                    fw_upload_send_val[0] = fw_upload_pack_id;
                    for (int x = 0; x < 16; x++)
                        fw_upload_send_val[1 + x] = firmware_data[fw_written_words * 4 + x];
                    fw_upload_send_valid = 1;
                    Log.e("uECG fw upload", "set send valid ");
                }
            }
        }
        if(fw_upload_state == 0)
        {
            Log.e("uECG fw upload ", "fill start code");
            int pos = 0;
            fw_upload_pack_id = 100;
            fw_upload_send_val[pos++] = fw_upload_pack_id;
            for(int n = 0; n < 8; n++)
                fw_upload_send_val[pos++] = fw_upload_start_code[n];
            fw_upload_send_val[pos++] = 0;
            fw_upload_send_val[pos++] = (byte)((firmware_len >> 16)&0xFF);
            fw_upload_send_val[pos++] = (byte)((firmware_len >> 8)&0xFF);
            fw_upload_send_val[pos++] = (byte)(firmware_len&0xFF);
            fw_upload_send_valid = 1;
            fw_upload_progress = 0;
        }
    }
    class FWUploadTM extends TimerTask {

        @Override
        public void run() {
            long ms = System.currentTimeMillis();
            if(fw_upload_state == 0 && ms - fw_upload_time < 100) return;
            if(ms - fw_upload_time < 55) return;
            if(fw_chr != null && ms - pending_char_ms > 1000)
            {
                Log.e("uECG fw upload", "upload timer");
                fw_upload_time = ms;
                if(fw_upload_send_valid > 0) {
                    Log.e("uECG fw upload", "send valid");
                    fw_upload_send_valid = 0;
                    fw_chr.setValue(fw_upload_send_val);
                    Log.e("uECG fw upload", "value set");

                    uecg_gatt.writeCharacteristic(fw_chr);
                    Log.e("uECG fw upload", "write char");
                    last_conn_data_tm = System.currentTimeMillis();
                    Log.e("uECG fw uploader", "pack " + fw_upload_send_val[0]);

                }
                else
                    fw_upload_process(0, (byte)0);
            }
        };

    };
    FWUploadTM fw_upload_tm_evt;
    Timer fw_upload_tim;
    MyGattCallback gatt_cb = new MyGattCallback();
    BluetoothGatt uecg_gatt = null;
    BluetoothDevice uecg_dev = null;
    long last_conn_data_tm = 0;
    int in_conn_restart = 0;
    int gatt_proc_started = 0;

    private class gtScanCb extends ScanCallback {
        Context ctx;
        void set_context(Context c)
        {
            ctx = c;
        };
        @Override
        public void onScanResult(int callbackType, ScanResult result) {

            Intent intent = new Intent("uECG_SIGNAL");
            intent.putExtra("signal", ble_uecg_service.UECG_CONNECT_UPDATE);
            Bundle b = new Bundle();
            b.putInt("type", 1); //scan event
            b.putString("message", "uECG device found, discovering services..."); //scan event
            intent.putExtra("conn_state", b);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            sendBroadcast(intent);

            if(gatt_proc_started == 0) {
                gatt_proc_started = 1;
                uecg_dev = result.getDevice();
                uecg_gatt = uecg_dev.connectGatt(ctx, false, gatt_cb);
                uecg_gatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH);
            }
        }

    }

    int bt_restart_issued = 0;
    class ConnectionTM extends TimerTask {

        Context ctx;
        void set_context(Context c)
        {
            ctx = c;
        };
        @Override
        public void run() {
            supervision_timer_working = 1;
            long ms = System.currentTimeMillis();
            Log.e("uECG", "supervision timer: " + in_conn_restart + " , " + (ms - last_conn_data_tm ));
            if(bt_restart_issued > 0)
            {
                connect_started = 0;
                gatt_proc_started = 0;
                if(!mBluetoothAdapter.isEnabled() && bt_restart_issued == 1)
                {
                    mBluetoothAdapter.enable();
                    bt_restart_issued = 2;
                    Log.e("uECG", "enabling BLE");
                }
                if(mBluetoothAdapter.isEnabled() && bt_restart_issued == 2)
                {
                    bt_restart_issued = 0;
                    Log.e("uECG", "starting scan");

                    Intent intent = new Intent("uECG_SIGNAL");
                    intent.putExtra("signal", ble_uecg_service.UECG_CONNECT_UPDATE);
                    Bundle b = new Bundle();
                    b.putInt("type", 3);
                    b.putString("message", "BLE scanning...");
                    intent.putExtra("conn_state", b);
                    intent.addCategory(Intent.CATEGORY_DEFAULT);
                    sendBroadcast(intent);

                    mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
                    settings = new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build();
                    filters = new ArrayList<ScanFilter>();
                    ScanFilter uuidFilter = new ScanFilter.Builder()
                            .setDeviceAddress(dev_mac).build();
                    filters.add(uuidFilter);
                    gtScanCb cb = new gtScanCb();
                    cb.set_context(ctx);
                    mLEScanner.startScan(filters, settings, cb);
                    in_conn_restart = 1;
                    last_conn_data_tm = ms;
                }
            }
            if(ms - last_conn_data_tm > 20000 && bt_restart_issued == 0)
            {
                if(cur_gatt_state == BluetoothProfile.STATE_CONNECTED) {
                    if (uecg_gatt != null)
                        uecg_gatt.disconnect();
                    cur_gatt_state = BluetoothProfile.STATE_DISCONNECTING;
                    last_conn_data_tm = ms;
                    return;
                }
                if(cur_gatt_state != BluetoothProfile.STATE_CONNECTED)
                {
                    //need to reset connection, something is wrong
                    mBluetoothAdapter.disable();
                    bt_restart_issued = 1;
                    Log.e("uECG", "disabling BLE");
                }
            }
            if(ms - last_conn_data_tm > 50000) //something went wrong
            {
                mBluetoothAdapter.disable();
                bt_restart_issued = 1;
                Log.e("uECG", "disabling2 BLE");
                last_conn_data_tm = ms;
            }

        };

    };
    ConnectionTM conn_tm_evt;
    int connect_started = 0;
    BluetoothDevice bt_dev_cache;
    void ble_uecg_connect(BluetoothDevice dev)
    {
        if(connect_started > 0) return;
        connect_started = 1;
        mLEScanner.stopScan(mScanCallback);
        bt_dev_cache = dev;
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                uecg_dev = bt_dev_cache;
                uecg_gatt = bt_dev_cache.connectGatt(uecg_service.getApplicationContext(), false, gatt_cb);
                uecg_gatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH);
                if(supervision_timer_working == 0) {
                    tim_conn = new Timer();
                    conn_tm_evt = new ConnectionTM();
//                    conn_tm_evt.set_context(this);
                    conn_tm_evt.set_context(uecg_service.getApplicationContext());
                    tim_conn.schedule(conn_tm_evt, 3000, 2000);
                }
                last_conn_data_tm = System.currentTimeMillis();
            }
        }, 2000);
    };

    int parse_result(byte[] scanRecord, int rssi, BluetoothDevice dev)
    {
        String name = dev.getName();
        String mac = dev.getAddress();
        if(name != null) if(name.contains("uECG") && name.length() < 6) {
            if (mac.contains("BA:BE")) {
                if (rssi > -85 && dev_mac.length() < 2) {
                    dev_mac = mac;
                    Log.e("uECG", "device detected, mac: " + mac);
                    restart_scan_bluetooth();
                    return 0;
                }
            }
        }
        if(name != null) if(name.contains("uECG v4.")) {
            if (rssi > -85 && dev_mac.length() < 2) {
                dev_mac = mac;
                ver_4_plus = 1;
                Log.e("uECG", "device 4.+ detected, mac: " + mac);

                Intent intent = new Intent("uECG_SIGNAL");
                intent.putExtra("signal", ble_uecg_service.UECG_CONNECT_UPDATE);
                Bundle b = new Bundle();
                b.putInt("type", 1); //scan event
                b.putString("message", "uECG device found, discovering services..."); //scan event
                intent.putExtra("conn_state", b);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                sendBroadcast(intent);

                ble_uecg_connect(dev);
                return 0;
            }
        }
        if(name != null) if(fw_upload_requested && name.contains("uECG boot")) {
            if (rssi > -85 && dev_mac.length() < 2) {
                dev_mac = mac;
                Log.e("uECG", "device boot detected, mac: " + mac);
                ble_uecg_connect(dev);
                return 0;
            }
        }

        if(mac.equals(dev_mac) && ver_4_plus == 1)
        {
            Log.e("uECG", "device 4.+ detected, mac: " + mac);
            ble_uecg_connect(dev);
            return 0;
        }
        if(mac.equals(dev_mac) && ver_4_plus == 0)
            if(uecg_parser.parse_record(scanRecord) > 0)
            {
                if(uecg_parser.parsed_state.has_batt)
                    batt = uecg_parser.parsed_state.batt;
                if(uecg_parser.parsed_state.has_BPM)
                    dev_BPM = uecg_parser.parsed_state.BPM;
                if(uecg_parser.parsed_state.has_SDNN)
                    dev_SDRR = uecg_parser.parsed_state.SDNN;
                if(uecg_parser.parsed_state.has_RMSSD)
                    dev_RMSSD = uecg_parser.parsed_state.RMSSD;
                if(uecg_parser.parsed_state.has_GSR)
                    dev_skin = uecg_parser.parsed_state.GSR;
                if(uecg_parser.parsed_state.has_accel) {
                    dev_ax = uecg_parser.parsed_state.ax;
                    dev_ay = uecg_parser.parsed_state.ay;
                    dev_az = uecg_parser.parsed_state.az;
                }
                if(uecg_parser.parsed_state.has_steps)
                    dev_steps = uecg_parser.parsed_state.steps;
                if(uecg_parser.parsed_state.pNN_bin1_id >= 0) {
                    pNN_short[uecg_parser.parsed_state.pNN_bin1_id] = uecg_parser.parsed_state.pNN_bin1;
                    pNN_short[uecg_parser.parsed_state.pNN_bin2_id] = uecg_parser.parsed_state.pNN_bin2;
                    pNN_short[uecg_parser.parsed_state.pNN_bin3_id] = uecg_parser.parsed_state.pNN_bin3;
                    if(uecg_parser.parsed_state.pNN_bin1_id > bins_count)
                        bins_count = uecg_parser.parsed_state.pNN_bin1_id;
                    if(uecg_parser.parsed_state.pNN_bin2_id > bins_count)
                        bins_count = uecg_parser.parsed_state.pNN_bin2_id;
                    if(uecg_parser.parsed_state.pNN_bin3_id > bins_count)
                        bins_count = uecg_parser.parsed_state.pNN_bin3_id;
                }
                had_new_RR = 0;
                if(uecg_parser.parsed_state.has_RR) {
                    if(uecg_parser.parsed_state.RR_id != dev_lastRR_id)
                    {
                        had_new_RR = 1;
                        rr_processor.push_data(uecg_parser.parsed_state.RR_cur, uecg_parser.parsed_state.RR_prev);
                        dev_lastRR_id = uecg_parser.parsed_state.RR_id;
                        float d_rr = uecg_parser.parsed_state.RR_cur - uecg_parser.parsed_state.RR_prev;
                        calc_SDNN *= 0.99;
                        calc_SDNN += 0.01 * d_rr * d_rr;
                        file_save_rr(uecg_parser.parsed_state.RR_cur, uecg_parser.parsed_state.RR_prev);
                    }
                }

                file_save_ecg();

                int d_id = uecg_parser.parsed_state.pack_id - prev_pack_id;
                prev_pack_id = uecg_parser.parsed_state.pack_id;
                if(d_id < 0) d_id += 256;
                draw_skip++;
                if(d_id == 0)
                    return 1;

                total_packs++;
                unsaved_cnt += d_id;

                ecg_processor.push_data(d_id, uecg_parser.parsed_state.ECG_values, uecg_parser.parsed_state.ECG_values_count, mark_data);
                if(uecg_parser.parsed_state.ECG_values_count > 0 && d_id > 0) mark_data = 0;

                bpm_processor.push_data(dev_BPM, dev_steps, dev_skin, rr_processor.get_hrv_parameter(), dev_ax, dev_ay, dev_az, dev_T);

                return 1;
            }

        return  0;
    };
    int init_file()
    {
        if(System.currentTimeMillis() - save_start_time < 5000) return 0;
        String state = Environment.getExternalStorageState();
        //Environment.DIRECTORY_DOCUMENTS;
        try
        {
            if (Environment.MEDIA_MOUNTED.equals(state)) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MMM-dd-HH-mm-ss");
                String ts = sdf.format(Calendar.getInstance().getTime());
                String log_name = "uECG/uecg_log_" + ts + ".csv";
                String log_name_ecg = "uECG/uecg_dat_" + ts + ".csv";

                Context ctx = getApplicationContext();
                log_file_just_created = 1;

                if (android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.P){
                    File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "uECG");
                    boolean res = dir.mkdirs();

                    log_file = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), log_name);
                    log_file_ecg = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), log_name_ecg);

                    log_file.createNewFile();
                    log_file_ecg.createNewFile();
                } else{
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOCUMENTS), "uECG");
                    log_file = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOCUMENTS), log_name);
                    log_file_ecg = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOCUMENTS), log_name_ecg);
                }

                save_start_time = System.currentTimeMillis();
                ecg_processor.set_save_time(save_start_time);

                return 1;
            }
            else
            {
                Log.e("uECG service", "path: " + Environment.DIRECTORY_DOCUMENTS + ", media state: " + state);
            }
        }catch(Exception ex)
        {
            Log.e("uECG service", "File save: got exception:" + ex.toString() + " when trying to access " + Environment.DIRECTORY_DOCUMENTS + ", media state: " + state);
//            Log.e(ex.getMessage());
        }
        return 0;
    };

    private class MyScanCallback extends ScanCallback {
        Context ctx;
        void set_context(Context c)
        {
            ctx = c;
        };
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            had_data = 1;
//            Log.e("uECG service", "scan callback ok");
            try {
//                if(result.getDevice().getAddress().contains("BA:BE"))
//                {
//                    if (result.getRssi() > -70 && dev_mac.length() < 2) {
//                        dev_mac = result.getDevice().getAddress();
//                        restart_scan_bluetooth();
//                    }
//                }
                no_scan_data_cnt = 0;

                int res = parse_result(result.getScanRecord().getBytes(), result.getRssi(), result.getDevice());
                if(res == 0)
                {
                    last_scan_restart = System.currentTimeMillis();
                    return;
                }
                Intent intent = new Intent("uECG_DATA");
                // You can also include some extra data.
//                intent.putExtra("Status", msg);
                Bundle b = new Bundle();
                b.putInt("batt", batt);
                b.putInt("dev_BPM", dev_BPM);
                b.putInt("dev_SDRR", dev_SDRR);
                b.putInt("dev_RMSSD", dev_RMSSD);
                b.putInt("dev_skin", dev_skin);
                b.putFloat("dev_ax", dev_ax);
                b.putFloat("dev_ay", dev_ay);
                b.putFloat("dev_az", dev_az);
                b.putInt("dev_steps", dev_steps);
                b.putInt("dev_BPM", dev_BPM);
                b.putInt("dev_pNN_bins", bins_count);
                b.putIntArray("pNNs", pNN_short);

                b.putInt("has_RR", had_new_RR);
                if(had_new_RR > 0)
                {
                    b.putInt("RR_cur", uecg_parser.parsed_state.RR_cur);
                    b.putInt("RR_prev", uecg_parser.parsed_state.RR_prev);
                    b.putFloat("RR_rmssd5m", rr_processor.RMSSD_5m);
                }

                b.putInt("pack_id", uecg_parser.parsed_state.pack_id);
                b.putInt("RR_count", uecg_parser.parsed_state.ECG_values_count);
                b.putIntArray("RR", uecg_parser.parsed_state.ECG_values);

                if(log_file != null) {
                    b.putString("file_path", log_file.getAbsolutePath());
                    b.putBoolean("file_state", log_file.canWrite());
                }

                intent.putExtra("BLE_data", b);

                intent.addCategory(Intent.CATEGORY_DEFAULT);
                sendBroadcast(intent);

                last_scan_restart = System.currentTimeMillis();
            }
            catch (Exception e) {
                Log.e("uECG service", "scan callback exception: " + e.toString());
            }

        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            Log.e("uECG service", "scan result list");
        }

        @Override
        public void onScanFailed(int errorCode) {
            had_err++;
            Log.e("uECG service", "scan failed " + errorCode);
        }
    };
    MyScanCallback mScanCallback = null;
    void restart_scan_bluetooth()
    {
        if(gatt_connection_type > 0) return;
        Intent intent = new Intent("uECG_SIGNAL");
        intent.putExtra("signal", ble_uecg_service.UECG_CONNECT_UPDATE);
        Bundle b = new Bundle();
        b.putInt("type", 3);
        b.putString("message", "BLE restarting...");
        intent.putExtra("conn_state", b);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        sendBroadcast(intent);

        Log.e("uECG", "scan restart");
        last_scan_restart = System.currentTimeMillis();
        if(mLEScanner == null)
        {
            Log.e("uECG", "LEScanner is null");
            mBluetoothAdapter.disable();
            bt_inited = -1;
            try { Thread.sleep(500);
            }catch (Exception e)
            { ; }
            return;
        }
        mLEScanner.stopScan(mScanCallback);
        mLEScanner.flushPendingScanResults(mScanCallback);
        try { Thread.sleep(500);
        }catch (Exception e)
        { ; }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
//                    .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                .setReportDelay(0L)
                .build();
        filters = new ArrayList<ScanFilter>();
        if(dev_mac.length() > 2) {
            ScanFilter uecg_mac = new ScanFilter.Builder().setDeviceAddress(dev_mac).build();
            filters.add(uecg_mac);
            Log.e("uECG", "scan restart by mac: " + dev_mac);
        }
//        else {
//            ScanFilter uecg_name = new ScanFilter.Builder().setDeviceName("uECG*").build();
//            filters.add(uecg_name);
//            Log.e("uECG", "scan restart by name: " + uecg_name);
//        }
        mLEScanner.startScan(filters, settings, mScanCallback);
        Log.e("uECG", "scan restart ok");
    }
    void scan_bluetooth() {
        Log.e("uECG", "scan start, ble addr: " + mBluetoothAdapter.getAddress());
        last_scan_restart = System.currentTimeMillis();

        if(!mBluetoothAdapter.isEnabled()) {
            mBluetoothAdapter.enable();
            try { Thread.sleep(500);
            }catch (Exception e)
            { ; }
        }
        if(mLEScanner == null)
        {
            mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
            if(mLEScanner == null) {
                Log.e("uECG", "mLEScanner still null");
                return;
            }
        }
        if(mScanCallback == null) {
            Log.e("uECG", "no scan callback");
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
//                    .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                    .setReportDelay(0L)
                    .build();
            filters = new ArrayList<ScanFilter>();
            ScanFilter uecg_name = new ScanFilter.Builder().setDeviceName("uECG").build();
            if(dev_mac.length() > 2) {
                ScanFilter uecg_mac = new ScanFilter.Builder().setDeviceAddress(dev_mac).build();
                filters.add(uecg_mac);
            }
//            else
//                filters.add(uecg_name);
            mScanCallback = new ble_uecg_service.MyScanCallback();
            mScanCallback.set_context(getApplicationContext());
            Log.e("uECG", "starting scan");
            mLEScanner.startScan(filters, settings, mScanCallback);
            bt_inited = 10;
        }
        else {
            Log.e("uECG", "scan callback exists");
            if(bt_inited >= 1 && mScanCallback != null && mLEScanner != null) {
                mLEScanner.stopScan(mScanCallback);
                mLEScanner.flushPendingScanResults(mScanCallback);
                try { Thread.sleep(500);
                }catch (Exception e)
                { ; }
                bt_inited = 1;
            }
            mScanCallback = null;
//            stopScanning();
//            timer.cancel();
//            timer.purge();
        }
//        mBluetoothAdapter.getBluetoothLeScanner().startScan(null, ScanSettings.SCAN_MODE_LOW_LATENCY, mLeScanCallback);
    };
    int no_scan_data_cnt = 0;
    class BLE_Checker extends TimerTask {

        private long alive_sent_time_ms = 0;
        @Override
        public void run() {
            long ms = System.currentTimeMillis();
            if(ms - alive_sent_time_ms > 5000) {
                alive_sent_time_ms = ms;
                Intent intent = new Intent("uECG_SIGNAL");
                if(fw_upload_progress < 1000) {
                    intent.putExtra("signal", ble_uecg_service.UECG_ALIVE_INDICATOR);
                    intent.addCategory(Intent.CATEGORY_DEFAULT);
                }
                else
                {
                    intent.putExtra("signal", ble_uecg_service.UECG_FW_UPLOAD_RESPONSE);
                    Bundle b = new Bundle();
                    b.putInt("progress", fw_upload_progress); //finished
                    intent.putExtra("FW_upload_progress", b);
                    fw_upload_progress = 101; //finished and sent
                }
                sendBroadcast(intent);
            }
            else if(fw_upload_requested)
            {
                Intent intent = new Intent("uECG_SIGNAL");
                intent.putExtra("signal", ble_uecg_service.UECG_FW_UPLOAD_RESPONSE);
                Bundle b = new Bundle();
                b.putInt("progress", fw_upload_progress); //finished
                intent.putExtra("FW_upload_progress", b);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                sendBroadcast(intent);
            }
//            Log.e("uECG service", "timer event");
            if(stop_requested) {
                if(tim_conn != null)
                    tim_conn.cancel();
                if(uecg_gatt != null)
                    uecg_gatt.disconnect();
                stopScanning();
                try { Thread.sleep(100);
                }catch (Exception e)
                { ; }
                stop_requested = false;
                Log.e("uECG service", "stop processed");
//                uecg_service.stopForeground(true);
                stopForeground(true);
                timer.cancel();
                try { Thread.sleep(100);
                }catch (Exception e)
                { ; }
                return;
            }

            if(gatt_connection_type > 0) return;
            if (bt_inited < 1) {
                init_bluetooth();
                return;
            } else if (bt_inited < 10) {
                scan_bluetooth();
            }

            ms = System.currentTimeMillis();
            if(ms - last_scan_restart > 30000 && bt_inited == 10) {
                no_scan_data_cnt++;
                if(no_scan_data_cnt > 4)
                {
                    stopScanning();
                    bt_inited = -1;
                    no_scan_data_cnt = 0;
                    return;
                }
                restart_scan_bluetooth();
            }

            if(had_err >= 3 && had_data == 0) {
                mBluetoothAdapter.disable();
                bt_inited = -1;
                had_err = 0;
            }
            if(had_data > 0)
                had_err = 0;
            had_data = 0;
        };

    };


    public static final String CHANNEL_ID = "uECGServiceChannel";
    @Override
    public void onCreate() {
        Log.e("uECG service", "trying to create");
        super.onCreate();
        uecg_service = this;
        Log.e("uECG service", "created");
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e("uECG service", "on start");
        String input = intent.getStringExtra("inputExtra");
        createNotificationChannel();
        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        notificationIntent.setAction("main_action");
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("uECG receiver")
                .setContentText(input)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(1, notification);

        BroadcastReceiver ecg_service_info = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int sig = intent.getIntExtra ("signal", 0);
                if(sig == UECG_CLOSE_REQUEST)
                {
                    Intent intent_out = new Intent("uECG_SIGNAL");
                    intent_out.putExtra("signal", UECG_CLOSE_RESPONSE);
                    intent_out.addCategory(Intent.CATEGORY_DEFAULT);
                    sendBroadcast(intent_out);
                    Log.e("uECG service", "stop request");

                    stop_requested = true;
                }

                if(sig == UECG_PING_REQUEST)
                {
                    Intent intent_out = new Intent("uECG_SIGNAL");
                    intent_out.putExtra("signal", UECG_PING_RESPONSE);
                    intent_out.addCategory(Intent.CATEGORY_DEFAULT);
                    sendBroadcast(intent_out);
                }

                if(sig == UECG_SAVE_START_REQUEST)
                {
                    init_file();
                    save_on = 1;
                }
                if(sig == UECG_SAVE_STOP_REQUEST)
                {
                    save_on = 0;
                }
                if(sig == UECG_MARK_DATA_REQUEST)
                {
                    mark_data = intent.getIntExtra("mark_value", 100);
                    mark_data_rr = mark_data;
                    mark_time_last = System.currentTimeMillis();
                    mark_last = mark_data;
                }
                if(sig == UECG_PDF_SAVE_START_REQUEST || sig == UECG_SAVE_START_REQUEST)
                {
                    pdf_save = new PdfDocument();
                    pdf_save_on = 1;
                    pdf_save_page_time = System.currentTimeMillis();
                    pdf_page_num = 1;
                }
                if(sig == UECG_PDF_SAVE_STOP_REQUEST || sig == UECG_SAVE_STOP_REQUEST)
                {
                    pdf_save_process(1); //write all remaining data
                    pdf_save_on = 0;
                    //now find out filename and save bitmap
                    String state = Environment.getExternalStorageState();
                    //Environment.DIRECTORY_DOCUMENTS;
                    try
                    {
                        File pdf_file;
                        if (Environment.MEDIA_MOUNTED.equals(state)) {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MMM-dd-HH-mm-ss");
                            String ts = sdf.format(Calendar.getInstance().getTime());
                            String pdf_name = "uECG/record_" + ts + ".pdf";

                            Context ctx = getApplicationContext();

                            if (android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.P){
                                File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "uECG");
                                boolean res = dir.mkdirs();

                                pdf_file = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), pdf_name);
                            } else{
                                File dir = new File(Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOCUMENTS), "uECG");
                                pdf_file = new File(Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOCUMENTS), pdf_name);
                            }
                            FileOutputStream out = new FileOutputStream(pdf_file);
                            pdf_save.writeTo(out);
                            pdf_save.close();
                        }
                        else
                        {
                            Log.e("uECG service", "path: " + Environment.DIRECTORY_DOCUMENTS + ", media state: " + state);
                        }
                    }catch(Exception ex)
                    {
                        Log.e("uECG service", "File save: got exception:" + ex.toString() + " when trying to access " + Environment.DIRECTORY_DOCUMENTS + ", media state: " + state);
//            Log.e(ex.getMessage());
                    }

                }

                if(sig == UECG_MAKE_SNAPSHOT_REQUEST)
                {
                    int period = intent.getIntExtra("snapshot_length", 60);
                    Bitmap bmp = Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888);
                    Canvas img = new Canvas(bmp);
                    ecg_processor.draw_spanshot(img, period, period,0, 0, 0, 0, 0, 0);

                    //now find out filename and save bitmap
                    String state = Environment.getExternalStorageState();
                    //Environment.DIRECTORY_DOCUMENTS;
                    try
                    {
                        File png_file;
                        if (Environment.MEDIA_MOUNTED.equals(state)) {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MMM-dd-HH-mm-ss");
                            String ts = sdf.format(Calendar.getInstance().getTime());
                            String png_name = "uECG/snapshot__" + ts + ".png";

                            Context ctx = getApplicationContext();

                            if (android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.P){
                                File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "uECG");
                                boolean res = dir.mkdirs();

                                png_file = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), png_name);
                            } else{
                                File dir = new File(Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOCUMENTS), "uECG");
                                png_file = new File(Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOCUMENTS), png_name);
                            }
                            FileOutputStream out = new FileOutputStream(png_file);
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out); // bmp is your Bitmap instance
                                // PNG is a lossless format, the compression factor (100) is ignored
                        }
                        else
                        {
                            Log.e("uECG service", "path: " + Environment.DIRECTORY_DOCUMENTS + ", media state: " + state);
                        }
                    }catch(Exception ex)
                    {
                        Log.e("uECG service", "File save: got exception:" + ex.toString() + " when trying to access " + Environment.DIRECTORY_DOCUMENTS + ", media state: " + state);
//            Log.e(ex.getMessage());
                    }
                }

                if(sig == UECG_STATE_ARRAY_REQUEST)
                {
                    Log.e("uECG", "state array request");
                    Intent intent_out = new Intent("uECG_SIGNAL");
                    intent_out.putExtra("signal", UECG_STATE_ARRAY_RESPONSE);
                    intent_out.addCategory(Intent.CATEGORY_DEFAULT);
                    Bundle b = new Bundle();

                    b.putInt("file_save_state", save_on);
                    b.putInt("RR_count", rr_processor.buf_size);
                    b.putInt("RR_bufpos", rr_processor.buf_cur_pos);
                    int st_high = (int)(rr_processor.start_time>>24);
                    int st_low = (int)(rr_processor.start_time&0xFFFFFF);
                    b.putInt("RR_start_time_h", st_high);
                    b.putInt("RR_start_time_l", st_low);
                    b.putFloatArray("RR_cur", rr_processor.RR_cur_hist);
                    b.putFloatArray("RR_prev", rr_processor.RR_prev_hist);
                    b.putInt("ECG_count", ecg_processor.hist_depth);
                    b.putFloatArray("ECG_data", ecg_processor.points_hist);
                    b.putIntArray("ECG valid", ecg_processor.points_valid);
                    int[] uids_high = new int[ecg_processor.hist_depth];
                    int[] uids_low = new int[ecg_processor.hist_depth];
                    for(int x = 0; x < ecg_processor.hist_depth; x++) {
                        uids_high[x] = (int)(ecg_processor.points_uid[x] >> 24);
                        uids_low[x] = (int)(ecg_processor.points_uid[x]&0xFFFFFF);
                    }
                    b.putIntArray("ECG uid_h", uids_high);
                    b.putIntArray("ECG uid_l", uids_low);

                    b.putFloat("HRV_parameter", rr_processor.get_hrv_parameter());

                    b.putIntArray("bpm_processor_int", bpm_processor.pack_int_array());
                    b.putFloatArray("bpm_processor_float", bpm_processor.pack_float_array());
                    intent_out.putExtra("uecg_data", b);
                    sendBroadcast(intent_out);
                }
                if(sig == UECG_FW_UPLOAD_REQUEST)
                {
//                    Intent intent_out = new Intent("uECG_SIGNAL");
//                    intent_out.putExtra("signal", UECG_FW_UPLOAD_RESPONSE);
//                    intent_out.addCategory(Intent.CATEGORY_DEFAULT);
 //                   sendBroadcast(intent_out);
                    Bundle b = intent.getBundleExtra("FW_data");

                    firmware_len = b.getInt("firmware_lentgh");
                    firmware_data = b.getByteArray("firmware_data");

                    Log.e("uECG service", "fw upload request flen " + firmware_len + " dat len " + firmware_data.length);

                    fw_upload_requested = true;
                    fw_upload_tim = new Timer();
                    fw_upload_tm_evt = new FWUploadTM();
                    fw_upload_tim.schedule(fw_upload_tm_evt, 5000, 35);

                }

            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("uECG_SERVICE_SIGNAL");
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        registerReceiver(ecg_service_info, filter);

        timer = new Timer();

        last_scan_restart = System.currentTimeMillis() + 10000;
        timer.schedule(new ble_uecg_service.BLE_Checker(), 3000, 500);

        return START_NOT_STICKY;
    }
    @Override
    public void onDestroy() {
        timer.cancel();
        stopScanning();
        Log.e("uECG service", "on destroy");

        super.onDestroy();
    }
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "uECG Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}