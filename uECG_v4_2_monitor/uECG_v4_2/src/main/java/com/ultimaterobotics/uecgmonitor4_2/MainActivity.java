package com.ultimaterobotics.uecgmonitor4_2;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.os.Bundle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Timer;
import java.util.TimerTask;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static Context main_context;
    MainActivity main_activity;

    Intent ble_service_intent;

    int draw_mask = 0x01; //1 - default, 2 - Poincare
    int paused_mode = 0;
    float time_scroll = 0;
    float time_scroll_base = 0;

    ECG_processor ecg_processor = new ECG_processor();
    RR_processor rr_processor = new RR_processor();
    BPM_processor bpm_processor = new BPM_processor();

    pixel_UI pixUI = new pixel_UI();
    List<pixel_UI.GraphButton> buttons = new ArrayList<pixel_UI.GraphButton>();

    pixel_UI.GraphButton lbl_BPM;
    pixel_UI.GraphButton lbl_SDRR;
    pixel_UI.GraphButton lbl_steps;
    pixel_UI.GraphButton lbl_warning;
    pixel_UI.GraphButton lbl_message;
    pixel_UI.GraphButton lbl_save_file;
    pixel_UI.GraphButton bt_record;
    pixel_UI.GraphButton bt_mark_data;
    pixel_UI.GraphButton bt_rr_ecg;
    pixel_UI.GraphButton bt_range_1min;
    pixel_UI.GraphButton bt_range_5min;
    pixel_UI.GraphButton bt_range_20min;
    pixel_UI.GraphButton bt_range_2hr;
    pixel_UI.GraphButton bt_range_12hr;
    pixel_UI.GraphButton bt_BPM;
    pixel_UI.GraphButton bt_HRV;
    pixel_UI.GraphButton bt_GSR;
    pixel_UI.GraphButton bt_steps;
    pixel_UI.GraphButton bt_accel;

    pixel_UI.GraphButton bt_fw_update;
    pixel_UI.GraphButton bt_fw_select_file;
    pixel_UI.GraphButton bt_fw_start_device_scan;
    pixel_UI.GraphButton lbl_fw_upload_status;
    pixel_UI.GraphButton pb_fw_upload_progress;

    public File getFileFromUri(Uri uri, Context context) {
        if (uri == null) {
            return null;
        }
        String sch = uri.getScheme();
        Log.e("uECG file: ", sch);

        if(sch.indexOf("content", 0) >= 0)
                return getFileFromContentUri(uri, context);
        if(sch.indexOf("file", 0) >= 0)
                return new File(uri.getPath());
        return null;

    }
    private File getFileFromContentUri(Uri contentUri, Context context) {
        if (contentUri == null) {
            Log.e("uECG file:", "null content uri");
            return null;
        }
        File file = null;
        String filePath;
        String fileName;
        String[] filePathColumn = {MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME};
        ContentResolver contentResolver = context.getContentResolver();
        Cursor cursor = contentResolver.query(contentUri, filePathColumn, null,
                null, null);
        if (cursor != null) {
            cursor.moveToFirst();
            filePath = cursor.getString(cursor.getColumnIndex(filePathColumn[0]));
            fileName = cursor.getString(cursor.getColumnIndex(filePathColumn[1]));
            cursor.close();
            if (!TextUtils.isEmpty(filePath)) {
                file = new File(filePath);
            }
            if (filePath == null || TextUtils.isEmpty(filePath) || (!file.exists() || file.length() <= 0)) {
                filePath = getPathFromInputStreamUri(context, contentUri, fileName);
            }
            if (!TextUtils.isEmpty(filePath)) {
                file = new File(filePath);
            }
        }
        else
            Log.e("uECG file:", "null cursor");

        return file;
    }
    public String getPathFromInputStreamUri(Context context, Uri uri, String fileName) {
        InputStream inputStream = null;
        String filePath = null;
        Log.e("uECG file:", "input stream???");
        if (uri.getAuthority() != null) {
//            return null;
            try {
                inputStream = context.getContentResolver().openInputStream(uri);
                File file = createTemporalFileFrom(context, inputStream, fileName);
                filePath = file.getPath();

            } catch (Exception e) {
                Log.e("uECG file path: ", e.toString());
            } finally {
                try {
                    if (inputStream != null) {
                        inputStream.close();
                    }
                } catch (Exception e) {
                    Log.e("uECG file path: ", e.toString());
                }
            }
        }

        return filePath;
    }
    private File createTemporalFileFrom(Context context, InputStream inputStream, String fileName)
            throws IOException {
        File targetFile = null;

        if (inputStream != null) {
            int read;
            byte[] buffer = new byte[8 * 1024];
                         // I define the copy file path
            targetFile = new File(getCacheDir(), fileName);
            if (targetFile.exists()) {
                targetFile.delete();
            }
            OutputStream outputStream = new FileOutputStream(targetFile);

            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();

            try {
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return targetFile;
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == 822 && resultCode == RESULT_OK) { //select firmware file
            Uri selectedfile = data.getData(); //The uri with the location of the file
            Log.e("uECG", selectedfile.toString());
            try {
                File fw_file = getFileFromUri(selectedfile, main_context);// File(selectedfile.getPath());
                int flen = (int) fw_file.length();
                byte[] file_cont = new byte[flen+16];
                DataInputStream dis = new DataInputStream(new FileInputStream(fw_file));
                dis.read(file_cont, 0, flen);
                dis.close();
                Log.e("uECG file length: ", Long.toString(flen));
                fw_file_ready(file_cont, flen);
            }
            catch (Exception e)
            {
                Log.e("uECG file read: ", e.toString());
            }
//            fw_file
        }
    }

    byte[] device_firmware;
    int device_firmware_len = 0;

    void fw_file_ready(byte[] file_cont, int flen)
    {
        if(flen < 100 || flen > 125000)
        {
            lbl_fw_upload_status.text = "Firmware file invalid";
            return;
        }
        bt_fw_start_device_scan.draw_mask = 0x08;
        lbl_fw_upload_status.text = "Put the device into bootloader mode, then start scan";
        device_firmware = new byte[flen + 32]; //additional padding bytes in order to simplify processing
        for(int x = 0; x < flen; x++) device_firmware[x] = file_cont[x];
        device_firmware_len = flen;
    }
    void create_buttons()
    {
        ecg_processor.set_viewport(0, 0.6f, 1, 0.35f);
        rr_processor.set_viewport(0.03f, 0.2f, 0.85f, 0.72f);
        bpm_processor.set_viewport(0.03f, 0.01f, 0.9f, 0.9f);

        pixel_UI.GraphButton close = pixUI.new GraphButton();
        close.set_coords(0.01f, 0.01f, 0.07f, 0.04f);
        close.set_color_normal(30, 200, 70);
        close.set_color_toggled(130, 100, 70);
        close.text_size = 40;
        close.text = "";
        close.toggled_text = "";
        close.type = pixel_UI.GBType.toggle;
        close.draw_mask = 0x01;
        close.draw_cross = 1;
        close.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                Intent intent = new Intent("uECG_SERVICE_SIGNAL");
                intent.putExtra("signal", ble_uecg_service.UECG_CLOSE_REQUEST);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                sendBroadcast(intent);
                main_activity.finish();

            }});
        buttons.add(close);

        bt_fw_update = pixUI.new GraphButton();
        bt_fw_update.set_coords(0.72f, 0.93f, 0.25f, 0.05f);
        bt_fw_update.set_color_normal(30, 200, 70);
        bt_fw_update.set_color_toggled(130, 100, 70);
        bt_fw_update.text_size = 25;
        bt_fw_update.text = "UPDATE FIRMWARE";
        bt_fw_update.toggled_text = "";
        bt_fw_update.type = pixel_UI.GBType.touch;
        bt_fw_update.draw_mask = 0x02 | 0x08;
        bt_fw_update.draw_cross = 0;

        bt_fw_update.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                if (draw_mask != 0x08) {
                    draw_mask = 0x08;
                    bt_fw_start_device_scan.draw_mask = 0x0;
                    bt_fw_update.set_coords(0.82f, 0.93f, 0.15f, 0.05f);
                    bt_fw_update.text = "exit";
                    lbl_fw_upload_status.text = "First, select new firmware file";
                }
                else
                {
                    draw_mask = 0x01;
                    bt_fw_update.set_coords(0.72f, 0.93f, 0.25f, 0.05f);
                    bt_fw_update.text = "UPDATE FIRMWARE";
                    bt_rr_ecg.text = "HRV plot";
                }
            }
        });
        buttons.add(bt_fw_update);

        bt_fw_select_file = pixUI.new GraphButton();
        bt_fw_select_file.set_coords(0.2f, 0.13f, 0.25f, 0.05f);
        bt_fw_select_file.set_color_normal(30, 200, 70);
        bt_fw_select_file.set_color_toggled(130, 100, 70);
        bt_fw_select_file.text_size = 25;
        bt_fw_select_file.text = "Select .bin file";
        bt_fw_select_file.toggled_text = "";
        bt_fw_select_file.type = pixel_UI.GBType.touch;
        bt_fw_select_file.draw_mask = 0x08;
        bt_fw_select_file.draw_cross = 0;

        bt_fw_select_file.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {

                Intent intent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);

                startActivityForResult(Intent.createChooser(intent, "Select UECG firmware file (uECGvX.bin)"), 822);
            }

        });
        buttons.add(bt_fw_select_file);

        bt_fw_start_device_scan = pixUI.new GraphButton();
        bt_fw_start_device_scan.set_coords(0.2f, 0.23f, 0.25f, 0.05f);
        bt_fw_start_device_scan.set_color_normal(30, 200, 70);
        bt_fw_start_device_scan.set_color_toggled(130, 100, 70);
        bt_fw_start_device_scan.text_size = 25;
        bt_fw_start_device_scan.text = "Start device scan";
        bt_fw_start_device_scan.toggled_text = "";
        bt_fw_start_device_scan.type = pixel_UI.GBType.touch;
        bt_fw_start_device_scan.draw_mask = 0x0;
        bt_fw_start_device_scan.draw_cross = 0;

        bt_fw_start_device_scan.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                if(device_firmware_len < 1) return;
                lbl_fw_upload_status.text = "Scanning...";
                Intent intent = new Intent("uECG_SERVICE_SIGNAL");
                intent.putExtra("signal", ble_uecg_service.UECG_FW_UPLOAD_REQUEST);
                Bundle b = new Bundle();

                b.putInt("firmware_lentgh", device_firmware_len);
                b.putByteArray("firmware_data", device_firmware);
                intent.putExtra("FW_data", b);

                intent.addCategory(Intent.CATEGORY_DEFAULT);
                sendBroadcast(intent);
            }

        });
        buttons.add(bt_fw_start_device_scan);

        lbl_fw_upload_status = pixUI.new GraphButton();
        lbl_fw_upload_status.set_coords(0.05f, 0.33f, 0.8f, 0.06f);
        lbl_fw_upload_status.set_color_normal(30, 200, 70);
        lbl_fw_upload_status.text_size = 35;
        lbl_fw_upload_status.text = "First, select new firmware file";
        lbl_fw_upload_status.draw_rect = 0;
        lbl_fw_upload_status.type = pixel_UI.GBType.label;
        lbl_fw_upload_status.draw_mask = 0x08;
        buttons.add(lbl_fw_upload_status);

        pb_fw_upload_progress = pixUI.new GraphButton();
        pb_fw_upload_progress.set_coords(0.05f, 0.43f, 0.8f, 0.06f);
        pb_fw_upload_progress.set_color_normal(30, 200, 70);
        pb_fw_upload_progress.text = "";
        pb_fw_upload_progress.draw_rect = 1;
        pb_fw_upload_progress.draw_text = 0;
        pb_fw_upload_progress.type = pixel_UI.GBType.progress;
        pb_fw_upload_progress.draw_mask = 0x08;
        pb_fw_upload_progress.progress_percent = 0;
        buttons.add(pb_fw_upload_progress);

        bt_record = pixUI.new GraphButton();
        bt_record.set_coords(0.6f, 0.45f, 0.35f, 0.06f);
        bt_record.set_color_normal(30, 200, 70);
        bt_record.set_color_toggled(130, 100, 70);
        bt_record.text_size = 30;
        bt_record.text = "start recording";
        bt_record.toggled_text = "stop recording";
        bt_record.type = pixel_UI.GBType.toggle;
        bt_record.draw_mask = 0x01;

        bt_record.set_callback(new pixel_UI.SimpleCallback() {
                                @Override
                                public void callback(int act, float dx, float dy) {
                                    if (save_on == 1) {
                                        Intent intent = new Intent("uECG_SERVICE_SIGNAL");
                                        intent.putExtra("signal", ble_uecg_service.UECG_SAVE_STOP_REQUEST);
                                        intent.addCategory(Intent.CATEGORY_DEFAULT);
                                        sendBroadcast(intent);

//                                        save_on = 0;
                                    } else {
                                        Intent intent = new Intent("uECG_SERVICE_SIGNAL");
                                        intent.putExtra("signal", ble_uecg_service.UECG_SAVE_START_REQUEST);
                                        intent.addCategory(Intent.CATEGORY_DEFAULT);
                                        sendBroadcast(intent);

//                                        init_file();
//                                        save_on = 1;
                                    } }});
        buttons.add(bt_record);

        bt_mark_data = pixUI.new GraphButton();
        bt_mark_data.set_coords(0.7f, 0.25f, 0.2f, 0.06f);
        bt_mark_data.set_color_normal(30, 200, 70);
        bt_mark_data.set_color_toggled(130, 100, 70);
        bt_mark_data.text_size = 30;
        bt_mark_data.text = "Mark";
        bt_mark_data.type = pixel_UI.GBType.toggle;
        bt_mark_data.draw_mask = 0x0;

        bt_mark_data.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                Intent intent = new Intent("uECG_SERVICE_SIGNAL");
                intent.putExtra("signal", ble_uecg_service.UECG_MARK_DATA_REQUEST);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                sendBroadcast(intent);

                Timer tm = new Timer();
                TimerTask task = new TimerTask() {
                    public void run() {
                        Intent intent = new Intent("uECG_SERVICE_SIGNAL");
                        intent.putExtra("signal", ble_uecg_service.UECG_MARK_DATA_REQUEST);
                        intent.addCategory(Intent.CATEGORY_DEFAULT);
                        sendBroadcast(intent);
                    }
                };
                tm.schedule(task , 50);

                } });
        buttons.add(bt_mark_data);

        pixel_UI.GraphButton pause = pixUI.new GraphButton();
        pause.set_coords(0.1f, 0.45f, 0.22f, 0.06f);
        pause.set_color_normal(30, 200, 70);
        pause.set_color_toggled(130, 100, 70);
        pause.text_size = 30;
        pause.text = "pause";
        pause.toggled_text = "resume";
        pause.type = pixel_UI.GBType.toggle;
        pause.draw_mask = 0x01;

        pause.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                if (paused_mode == 1) {
                    paused_mode = 0;
                    time_scroll = 0;
                    time_scroll_base = 0;
                } else {
                    paused_mode = 1;
                }
                ecg_processor.set_pause_state(paused_mode);
            }});
        buttons.add(pause);

        bt_rr_ecg = pixUI.new GraphButton();
        bt_rr_ecg.set_coords(0.75f, 0.08f, 0.2f, 0.06f);
        bt_rr_ecg.set_color_normal(30, 200, 70);
        bt_rr_ecg.set_color_toggled(130, 100, 70);
        bt_rr_ecg.text_size = 30;
        bt_rr_ecg.text = "HRV plot";
        bt_rr_ecg.toggled_text = "ECG plot";
        bt_rr_ecg.type = pixel_UI.GBType.touch;
        bt_rr_ecg.draw_mask = 0x01 | 0x02 | 0x04;

        bt_rr_ecg.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                if (draw_mask == 0x01) {
                    draw_mask = 0x02;
                    bt_rr_ecg.text = "BPM plot";
                }
                else if(draw_mask == 0x02)
                {
                    draw_mask = 0x04;
                    bt_rr_ecg.text = "ECG plot";
                }
                else {
                    bt_rr_ecg.text = "HRV plot";
                    draw_mask = 0x01;
                }
            }});
        buttons.add(bt_rr_ecg);

        bt_range_1min = pixUI.new GraphButton();
        bt_range_1min.set_coords(0.01f, 0.9f, 0.15f, 0.06f);
        bt_range_1min.set_color_normal(30, 200, 70);
        bt_range_1min.set_color_toggled (230, 200, 70);
        bt_range_1min.text_size = 30;
        bt_range_1min.text = "1 min";
        bt_range_1min.type = pixel_UI.GBType.toggle;
        bt_range_1min.draw_mask = 0x04;

        bt_range_1min.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                bpm_processor.set_range(60);
                bt_range_5min.pressed = 0;
                bt_range_20min.pressed = 0;
                bt_range_2hr.pressed = 0;
                bt_range_12hr.pressed = 0;
            }});
        buttons.add(bt_range_1min);

        bt_range_5min = pixUI.new GraphButton();
        bt_range_5min.set_coords(0.2f, 0.9f, 0.15f, 0.06f);
        bt_range_5min.set_color_normal(30, 200, 70);
        bt_range_5min.set_color_toggled (230, 200, 70);
        bt_range_5min.text_size = 30;
        bt_range_5min.text = "5 min";
        bt_range_5min.type = pixel_UI.GBType.toggle;
        bt_range_5min.draw_mask = 0x04;
        bt_range_5min.pressed = 1;

        bt_range_5min.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                bpm_processor.set_range(5*60);
                bt_range_1min.pressed = 0;
                bt_range_20min.pressed = 0;
                bt_range_2hr.pressed = 0;
                bt_range_12hr.pressed = 0;
            }});
        buttons.add(bt_range_5min);

        bt_range_20min = pixUI.new GraphButton();
        bt_range_20min.set_coords(0.4f, 0.9f, 0.15f, 0.06f);
        bt_range_20min.set_color_normal(30, 200, 70);
        bt_range_20min.set_color_toggled (230, 200, 70);
        bt_range_20min.text_size = 30;
        bt_range_20min.text = "20 min";
        bt_range_20min.type = pixel_UI.GBType.toggle;
        bt_range_20min.draw_mask = 0x04;

        bt_range_20min.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                bpm_processor.set_range(20*60);
                bt_range_1min.pressed = 0;
                bt_range_5min.pressed = 0;
                bt_range_2hr.pressed = 0;
                bt_range_12hr.pressed = 0;
            }});
        buttons.add(bt_range_20min);

        bt_range_2hr = pixUI.new GraphButton();
        bt_range_2hr.set_coords(0.6f, 0.9f, 0.15f, 0.06f);
        bt_range_2hr.set_color_normal(30, 200, 70);
        bt_range_2hr.set_color_toggled (230, 200, 70);
        bt_range_2hr.text_size = 30;
        bt_range_2hr.text = "2 hr";
        bt_range_2hr.type = pixel_UI.GBType.toggle;
        bt_range_2hr.draw_mask = 0x04;

        bt_range_2hr.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                bpm_processor.set_range(2*60*60);
                bt_range_1min.pressed = 0;
                bt_range_5min.pressed = 0;
                bt_range_20min.pressed = 0;
                bt_range_12hr.pressed = 0;
            }});
        buttons.add(bt_range_2hr);

        bt_range_12hr = pixUI.new GraphButton();
        bt_range_12hr.set_coords(0.8f, 0.9f, 0.15f, 0.06f);
        bt_range_12hr.set_color_normal(30, 200, 70);
        bt_range_12hr.set_color_toggled (230, 200, 70);
        bt_range_12hr.text_size = 30;
        bt_range_12hr.text = "12 hrs";
        bt_range_12hr.type = pixel_UI.GBType.toggle;
        bt_range_12hr.draw_mask = 0x04;

        bt_range_12hr.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                bpm_processor.set_range(12*60*60);
                bt_range_1min.pressed = 0;
                bt_range_5min.pressed = 0;
                bt_range_20min.pressed = 0;
                bt_range_2hr.pressed = 0;
            }});
        buttons.add(bt_range_12hr);

        bt_BPM = pixUI.new GraphButton();
        bt_BPM.set_coords(0.05f, 0.01f, 0.15f, 0.06f);
        bt_BPM.set_color_normal(30, 200, 70);
        bt_BPM.set_color_toggled (30, 200, 70);
        bt_BPM.text_size = 30;
        bt_BPM.text = "BPM";
        bt_BPM.type = pixel_UI.GBType.checkbox;
        bt_BPM.chbox_size = 0.04f;
        bt_BPM.draw_mask = 0x04;
        bt_BPM.pressed = 1;
        bpm_processor.set_chart_state(BPM_processor.chart_ID_BPM, bt_BPM.pressed);
        bt_BPM.draw_rect = 0;

        bt_BPM.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                bpm_processor.set_chart_state(BPM_processor.chart_ID_BPM, bt_BPM.pressed);
            }});
        buttons.add(bt_BPM);

        bt_HRV = pixUI.new GraphButton();
        bt_HRV.set_coords(0.25f, 0.01f, 0.15f, 0.06f);
        bt_HRV.set_color_normal(30, 200, 70);
        bt_HRV.set_color_toggled (30, 200, 70);
        bt_HRV.text_size = 30;
        bt_HRV.text = "HRV";
        bt_HRV.type = pixel_UI.GBType.checkbox;
        bt_HRV.chbox_size = 0.04f;
        bt_HRV.draw_mask = 0x04;
        bt_HRV.pressed = 1;
        bpm_processor.set_chart_state(BPM_processor.chart_ID_HRV, bt_HRV.pressed);
        bt_HRV.draw_rect = 0;

        bt_HRV.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                bpm_processor.set_chart_state(BPM_processor.chart_ID_HRV, bt_HRV.pressed);
            }});
        buttons.add(bt_HRV);

        bt_GSR = pixUI.new GraphButton();
        bt_GSR.set_coords(0.45f, 0.01f, 0.15f, 0.06f);
        bt_GSR.set_color_normal(30, 200, 70);
        bt_GSR.set_color_toggled (30, 200, 70);
        bt_GSR.text_size = 30;
        bt_GSR.text = "GSR";
        bt_GSR.type = pixel_UI.GBType.checkbox;
        bt_GSR.chbox_size = 0.04f;
        bt_GSR.draw_mask = 0x04;
        bt_GSR.pressed = 1;
        bpm_processor.set_chart_state(BPM_processor.chart_ID_GSR, bt_GSR.pressed);
        bt_GSR.draw_rect = 0;

        bt_GSR.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                bpm_processor.set_chart_state(BPM_processor.chart_ID_GSR, bt_GSR.pressed);
            }});
        buttons.add(bt_GSR);

        bt_steps = pixUI.new GraphButton();
        bt_steps.set_coords(0.65f, 0.01f, 0.15f, 0.06f);
        bt_steps.set_color_normal(30, 200, 70);
        bt_steps.set_color_toggled (30, 200, 70);
        bt_steps.text_size = 30;
        bt_steps.text = "Steps";
        bt_steps.type = pixel_UI.GBType.checkbox;
        bt_steps.chbox_size = 0.04f;
        bt_steps.draw_mask = 0x04;
        bt_steps.pressed = 0;
        bpm_processor.set_chart_state(BPM_processor.chart_ID_steps, bt_steps.pressed);
        bt_steps.draw_rect = 0;

        bt_steps.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                bpm_processor.set_chart_state(BPM_processor.chart_ID_steps, bt_steps.pressed);
            }});
        buttons.add(bt_steps);

        bt_accel = pixUI.new GraphButton();
        bt_accel.set_coords(0.85f, 0.01f, 0.15f, 0.06f);
        bt_accel.set_color_normal(30, 200, 70);
        bt_accel.set_color_toggled (30, 200, 70);
        bt_accel.text_size = 30;
        bt_accel.text = "Accel";
        bt_accel.type = pixel_UI.GBType.checkbox;
        bt_accel.chbox_size = 0.04f;
        bt_accel.draw_mask = 0x04;
        bt_accel.pressed = 1;
        bpm_processor.set_chart_state(BPM_processor.chart_ID_acc, bt_accel.pressed);
        bt_accel.draw_rect = 0;

        bt_accel.set_callback(new pixel_UI.SimpleCallback() {
            @Override
            public void callback(int act, float dx, float dy) {
                bpm_processor.set_chart_state(BPM_processor.chart_ID_acc, bt_accel.pressed);
            }});
        buttons.add(bt_accel);


        pixel_UI.GraphButton ecg_scroll = pixUI.new GraphButton();
        ecg_scroll.set_coords(0, 0.6f, 1, 0.3f);
        ecg_scroll.draw_rect = 0;
        ecg_scroll.draw_text = 0;
        ecg_scroll.type = pixel_UI.GBType.scroll;
        ecg_scroll.draw_mask = 0x01;
        ecg_scroll.set_callback(new pixel_UI.SimpleCallback() {
            float scroll_prev_dx = 0;
            @Override
            public void callback(int act, float dx, float dy) {
                if(act == 1 || act == 2) {
                    time_scroll = time_scroll_base + 2.0f*dx;
                    if (time_scroll < 0) time_scroll = 0;
                    if (time_scroll > 150) time_scroll = 150;
                    if (act == 1) {
                        time_scroll_base = time_scroll;
                    }
                    ecg_processor.set_draw_shift(time_scroll);
                }
                if(act == 3)
                {
                    float chg = dx - scroll_prev_dx;
                    scroll_prev_dx = dx;
                    if(chg > 0) {
                        ecg_processor.screen_time *= 0.99;
                        if (ecg_processor.screen_time < 0.5) ecg_processor.screen_time = 0.5f;
                    }
                    if(chg < 0) {
                        ecg_processor.screen_time *= 1.01;
                        if (ecg_processor.screen_time > 30) ecg_processor.screen_time = 30;
                    }
//                    ecg_processor.set_screen_time( 15.0f / (1.0f + 4.0f*dx) );
//                    ecg_processor.set_screen_time( 2.5f / dx);
                }
            }});
        buttons.add(ecg_scroll);

        lbl_BPM = pixUI.new GraphButton();
        lbl_BPM.set_coords(0.05f, 0.35f, 0.1f, 0.06f);
        lbl_BPM.set_color_normal(30, 200, 70);
        lbl_BPM.text_size = 35;
        lbl_BPM.draw_rect = 0;
        lbl_BPM.type = pixel_UI.GBType.label;
        lbl_BPM.draw_mask = 0x01;
        buttons.add(lbl_BPM);

        lbl_SDRR = pixUI.new GraphButton();
        lbl_SDRR.set_coords(0.3f, 0.35f, 0.1f, 0.06f);
        lbl_SDRR.set_color_normal(30, 200, 70);
        lbl_SDRR.text_size = 35;
        lbl_SDRR.draw_rect = 0;
        lbl_SDRR.type = pixel_UI.GBType.label;
        lbl_SDRR.draw_mask = 0x01;
        buttons.add(lbl_SDRR);

        lbl_steps = pixUI.new GraphButton();
        lbl_steps.set_coords(0.60f, 0.35f, 0.1f, 0.06f);
        lbl_steps.set_color_normal(30, 200, 70);
        lbl_steps.text_size = 35;
        lbl_steps.draw_rect = 0;
        lbl_steps.type = pixel_UI.GBType.label;
        lbl_steps.draw_mask = 0x01;
        buttons.add(lbl_steps);

        lbl_warning = pixUI.new GraphButton();
        lbl_warning.set_coords(0.20f, 0.1f, 0.8f, 0.06f);
        lbl_warning.set_color_normal(230, 100, 70);
        lbl_warning.text_size = 35;
        lbl_warning.draw_rect = 0;
        lbl_warning.type = pixel_UI.GBType.label;
        lbl_warning.draw_mask = 0x00;
        buttons.add(lbl_warning);

        lbl_message = pixUI.new GraphButton();
        lbl_message.set_coords(0.1f, 0.01f, 0.5f, 0.06f);
        lbl_message.set_color_normal(30, 200, 70);
        lbl_message.text_size = 35;
        lbl_message.draw_rect = 0;
        lbl_message.type = pixel_UI.GBType.label;
        lbl_message.draw_mask = 0x01;
        buttons.add(lbl_message);

        lbl_save_file = pixUI.new GraphButton();
        lbl_save_file.set_coords(0.01f, 0.51f, 0.98f, 0.06f);
        lbl_save_file.set_color_normal(30, 200, 70);
        lbl_save_file.text_size = 25;
        lbl_save_file.draw_rect = 0;
        lbl_save_file.type = pixel_UI.GBType.label;
        lbl_save_file.text = "";
        lbl_save_file.draw_mask = 0x00;
        buttons.add(lbl_save_file);
    }

    int prev_pack_id = 0;
    int batt = 0;
    int draw_skip = 0;
    int[] bpm_chart = new int[100];
    int bpm_length = 100;
    long last_bpm_add = 0;

    float lost_points_avg = 0;
    float total_points_avg = 0;

    int total_packs = 0;

    uecg_ble_parser uecg_parser = new uecg_ble_parser();

    int dev_BPM = 0;
    int dev_BPM_momentary = 0;
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

    int unsaved_cnt = 0;

    int[] pNN_short = new int[32];

    String save_file_name = new String("");
    boolean save_file_state = false;

    int save_on = 0;

    Timer timer = new Timer();

    int data_push_ecg(Bundle b)
    {
        int d_id = b.getInt("pack_id") - prev_pack_id;
        if(d_id > 300) d_id = 0;
        prev_pack_id = b.getInt("pack_id");
        dev_BPM_momentary = b.getInt("BPM_momentary");
        if(d_id < 0) d_id += 256*256;
        if(d_id > 30000) //could be if we got packet from the past due to async call: d_id will be slightly negative instead of proper overflow case
        {
            return  1;
        }
        if(d_id < 0) d_id = 0;
        draw_skip++;
        if(d_id == 0)
            return 1;
        if(d_id > 300) d_id = 300; //can't be that much, something went wrong
        total_packs++;
        unsaved_cnt += d_id;

        ecg_processor.push_data(d_id, b.getIntArray("RR"), b.getInt("RR_count"), 0);

        if(bt_record.pressed > 0)
        {
            bt_mark_data.draw_mask = 0x01;
            lbl_save_file.draw_mask = 0x01;
            lbl_save_file.text = "File: " + b.getString ("file_path");
            if(b.getBoolean("file_state"))
                lbl_save_file.set_color_normal(30, 200, 70);
            else
                lbl_save_file.set_color_normal(200, 30, 30);
        }
        else {
            lbl_save_file.draw_mask = 0x00;
            bt_mark_data.draw_mask = 0x00;
        }
        if(bt_mark_data.pressed > 0 && bt_mark_data.pressed_time + 150 < System.currentTimeMillis())
            bt_mark_data.pressed = 0;

        return 1;
    }
    int data_push_hrv(Bundle b)
    {
        int bins = b.getInt("dev_pNN_bins");
        if(bins > 32) bins = 32;
        for(int x = 0; x < bins; x++)
            pNN_short[x] = b.getIntArray("pNNs")[x];
        batt = b.getInt("batt");
        dev_SDRR = b.getInt("dev_SDRR");
        dev_RMSSD = b.getInt("dev_RMSSD");
        return  1;
    }
    int data_push_imu_rr(Bundle b)
    {
        dev_ax = b.getFloat("dev_ax");
        dev_ay = b.getFloat("dev_ay");
        dev_az = b.getFloat("dev_az");
        dev_wx = b.getFloat("dev_wx");
        dev_wy = b.getFloat("dev_wy");
        dev_wz = b.getFloat("dev_wz");
        dev_T = b.getFloat("dev_T");
        dev_steps = b.getInt("dev_steps");
        dev_BPM = b.getInt("dev_BPM");
        dev_skin = b.getInt("dev_skin");
        int has_RR = b.getInt("has_RR");
        if(has_RR > 0)
        {
            rr_processor.push_data(b.getInt("RR_cur"), b.getInt("RR_prev"));
        }
        bpm_processor.push_data(dev_BPM, dev_steps, dev_skin, rr_processor.get_hrv_parameter(), dev_ax, dev_ay, dev_az);
        return 1;
    }

    int update_ecg_data(Bundle b)
    {
        batt = b.getInt("batt");
        dev_BPM = b.getInt("dev_BPM");
        dev_SDRR = b.getInt("dev_SDRR");
        dev_RMSSD = b.getInt("dev_RMSSD");
        dev_skin = b.getInt("dev_skin");
        dev_ax = b.getFloat("dev_ax");
        dev_ay = b.getFloat("dev_ay");
        dev_az = b.getFloat("dev_az");
        dev_steps = b.getInt("dev_steps");
        int bins = b.getInt("dev_pNN_bins");
        if(bins > 32) bins = 32;
        for(int x = 0; x < bins; x++)
            pNN_short[x] = b.getIntArray("pNNs")[x];
        int has_RR = b.getInt("has_RR");
        if(has_RR > 0)
        {
            rr_processor.push_data(b.getInt("RR_cur"), b.getInt("RR_prev"));
            float d_rr = b.getInt("RR_cur") - b.getInt("RR_prev");
            calc_SDNN *= 0.99;
            calc_SDNN += 0.01 * d_rr * d_rr;
        }
        int d_id = b.getInt("pack_id")- prev_pack_id;
        if(d_id > 300) d_id = 0;
        prev_pack_id = b.getInt("pack_id");
        if(d_id < 0 && prev_pack_id < 300) d_id += 256;
        if(d_id < 0 && prev_pack_id >= 300) d_id += 256*256;
        if(d_id < 0) d_id = 0;
        draw_skip++;
        if(d_id == 0)
            return 1;

        total_packs++;
        unsaved_cnt += d_id;

        ecg_processor.push_data(d_id, b.getIntArray("RR"), b.getInt("RR_count"), 0);
        bpm_processor.push_data(dev_BPM, dev_steps, dev_skin, rr_processor.get_hrv_parameter(), dev_ax, dev_ay, dev_az);

        if(bt_record.pressed > 0)
        {
            bt_mark_data.draw_mask = 0x01;
            lbl_save_file.draw_mask = 0x01;
            lbl_save_file.text = "File: " + b.getString ("file_path");
            if(b.getBoolean("file_state"))
                lbl_save_file.set_color_normal(30, 200, 70);
            else
                lbl_save_file.set_color_normal(200, 30, 30);
        }
        else {
            lbl_save_file.draw_mask = 0x00;
            bt_mark_data.draw_mask = 0x00;
        }
        if(bt_mark_data.pressed > 0 && bt_mark_data.pressed_time + 150 < System.currentTimeMillis())
            bt_mark_data.pressed = 0;

        return 1;
    }

    long ping_request_sent = 0;
    int ping_pending = 0;
    int location_requested = 0;

    class drawUITask extends TimerTask {

        @Override
        public void run() {
            MainActivity.this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    long ms = System.currentTimeMillis();
                    if(ms - ping_request_sent > 2000 && ping_pending == 11) {
                        Log.e("uECG", "no response from service, starting");
                        //if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ble_service_intent = new Intent(getApplicationContext(), ble_uecg_service.class);
                            ble_service_intent.putExtra("inputExtra", "uECG receiver process is running");
                            ContextCompat.startForegroundService(getApplicationContext(), ble_service_intent);
                            Log.e("uECG", "starting service");
                        //}
                        //else {
                         //   ble_service_intent = new Intent(getApplicationContext(), ble_uecg_service_compat25l.class);
                          //  ble_service_intent.putExtra("inputExtra", "uECG receiver process is running");
                           // ContextCompat.startForegroundService(getApplicationContext(), ble_service_intent);
                           // Log.e("uECG", "starting service for <26 API");
                       // }
//                        startForegroundService(ble_service_intent);
                        ping_pending = 0;
                    }
                    int bat_v = 0;
                    bat_v = 2000 + batt*10;
                    float lost_perc = 0;
                    if(total_points_avg > 1)
                        lost_perc = (float)(Math.round((float)(lost_points_avg / total_points_avg * (float)1000.0))/10.0);
                    double bat_perc = 0;
                    if(bat_v > 3100)
                    {
                        if(bat_v < 3300)
                            bat_perc = (bat_v - 3100) / 10.0;
                        else
                            bat_perc = 20 + (bat_v - 3300) / 11.25;
                        if(bat_perc > 100) bat_perc = 100;
                    }
                    bat_perc = Math.floor(bat_perc);
                    lbl_message.text = "Battery: " + bat_v + " mV" + " - " + bat_perc + "%";
                    if(bat_v < 2100) lbl_message.text = "Searching for uECG device...";
                    SurfaceView sv = (SurfaceView)findViewById(R.id.surfaceView);
                    if(!sv.getHolder().getSurface().isValid()) return;
                    Canvas img = sv.getHolder().getSurface().lockCanvas(null);
                    Paint pnt = new Paint();
                    int width = img.getWidth();
                    int height = img.getHeight();
                    pnt.setARGB(255, 0, 0, 0);
                    img.drawRect(0, 0, width, height, pnt);
                    pnt.setARGB(255, 50, 255, 70);

                    if(draw_mask == 0x01)
                        ecg_processor.draw(img);
                    if(draw_mask == 0x02)
                        rr_processor.draw(img);
                    if(draw_mask == 0x04)
                        bpm_processor.draw(img);

                    if(draw_mask == 0x01)
                        for(int b = 0; b < 15; b++)
                    {
                        int bw = (int)(width/15.0*0.6);
                        int cur_x = (int)(width*0.05) + b*(int)(bw+5);
                        int cur_ys = (int)(height*0.20 - 0.2*height * pNN_short[b] / 255.0);
                        pnt.setTextSize(15);
                        pnt.setARGB(255, 0, 200, 100);
                        img.drawRect(cur_x, cur_ys, cur_x+bw, (int)(height*0.2), pnt);
                        img.drawText("" + (int)(pNN_short[b]/2.55), cur_x, cur_ys-15, pnt);

                    }

                    int bpm = dev_BPM;
                    long tm = System.currentTimeMillis();
                    if(tm - last_bpm_add > 10000)
                    {
                        last_bpm_add = tm;
                        for(int x = 0; x < bpm_length-1; x++)
                            bpm_chart[x] = bpm_chart[x+1];
                        bpm_chart[bpm_length-1] = bpm;
                    }

                    lbl_BPM.text = "BPM: " + dev_BPM;
                    lbl_SDRR.text = "SDRR: " + dev_SDRR; //dev_SDNN;
                    lbl_steps.text = "Steps: " + dev_steps;

                    if(!is_location_enabled(main_context))
                    {
                        lbl_warning.text = "Please turn on location";
                        lbl_warning.draw_mask = 0x01;
                        if(location_requested < 1)
                        {
                            location_requested = 1;
                            ActivityCompat.requestPermissions(main_activity,
                                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                    12345);
                        }
                    }
                    else
                    {
                        lbl_warning.text = "";
                        lbl_warning.draw_mask = 0x0;
                    }

                    for (int i = 0; i < buttons.size(); i++)
                        buttons.get(i).draw(img, draw_mask);

                    sv.getHolder().getSurface().unlockCanvasAndPost(img);

                }
            });
        }
    };

    int press_x = 0;
    int press_y = 0;
    int press_dx = 0;
    int press_dy = 0;
    int zoom_in_progress = 0;
    public boolean touch_process(MotionEvent e){
        SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
        float width = sv.getWidth();
        float height = sv.getHeight();

        if(e.getPointerCount() == 2) //possible zoom event
        {
            int event_type = 0;
            int e_code = e.getActionMasked();
            if(e_code == MotionEvent.ACTION_MOVE)
            {
                float x0 = e.getX(0) / width;
                float y0 = e.getY(0) / height;
                int cur_dx = (int) Math.floor(e.getX(0) - e.getX(1));
                int cur_dy = (int) Math.floor(e.getY(0) - e.getY(1));
                if(zoom_in_progress == 0)
                {
                    zoom_in_progress = 1;
                    press_dx = cur_dx;
                    press_dy = cur_dy;
                }
                float sx = Math.abs((float)cur_dx) / (1.0f + Math.abs((float)press_dx));
                float sy = Math.abs((float)cur_dy) / (1.0f + Math.abs((float)press_dy));
                for (int i = 0; i < buttons.size(); i++)
                    buttons.get(i).process_touch(3, x0, y0, sx, sy);
            }
            if(e_code == MotionEvent.ACTION_UP)
            {
                zoom_in_progress = 0;
            }
            return  true;
        }
        int tx = (int) Math.floor(e.getX());
        int ty = (int) Math.floor(e.getY());
        int event_type = 0;
        int e_code = e.getActionMasked();
        if(e_code == MotionEvent.ACTION_DOWN)
        {
            press_x = tx;
            press_y = ty;
        }
        if(e_code == MotionEvent.ACTION_UP)
        {
//            if(Math.abs(press_x - tx) + Math.abs(press_y - ty) < 30)
            event_type = 1;
        }
        if(e_code == MotionEvent.ACTION_MOVE)
        {
            event_type = 2;
        }
        if(event_type > 0) {

            float x = (float) tx / width;
            float y = (float) ty / height;

            float sx = (float) press_x / width;
            float sy = (float) press_y / height;
            for (int i = 0; i < buttons.size(); i++)
                buttons.get(i).process_touch(event_type, x, y, sx, sy);
        }
//        if(event_type == 0)
            return true;
//        else
//S            return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        main_activity = this;
        main_context = this.getApplicationContext();

        setContentView(R.layout.activity_main);

        BroadcastReceiver uecg_data_receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle b = intent.getBundleExtra("BLE_data");
                if(b != null) update_ecg_data(b);
//                parse_result(b.getByteArray("data"), b.getInt("type"), b.getInt("RSSI"), b.getString("MAC"));

            }
        };

        BroadcastReceiver data_receiver_ecg = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle b = intent.getBundleExtra("BLE_data_ecg");
                if(b != null) data_push_ecg(b);
            }
        };
        BroadcastReceiver data_receiver_hrv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle b = intent.getBundleExtra("BLE_data_hrv");
                if(b != null) data_push_hrv(b);
            }
        };
        BroadcastReceiver data_receiver_imu_rr = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle b = intent.getBundleExtra("BLE_data_imu_rr");
                if(b != null) data_push_imu_rr(b);
            }
        };

        BroadcastReceiver ecg_service_info = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int sig = intent.getIntExtra ("signal", 0);
                if(sig == ble_uecg_service.UECG_CLOSE_RESPONSE) {
                    Log.e("uECG", "close response - closing");
                    main_activity.finish();
                }
                if(sig == ble_uecg_service.UECG_ALIVE_INDICATOR)
                {
                    ping_pending = 0;
                }
                if(sig == ble_uecg_service.UECG_STATE_ARRAY_RESPONSE)
                {
                    Log.e("uECG", "state array response");
                    Bundle b = intent.getBundleExtra("uecg_data");

                    save_on = b.getInt("file_save_state");
                    if(save_on > 0)
                    {
                        bt_record.pressed = 1;
                    }

                    int rr_cnt = b.getInt("RR_count");
                    rr_processor.buf_size = rr_cnt;
                    rr_processor.buf_cur_pos = b.getInt("RR_bufpos");
                    int st_high = b.getInt("RR_start_time_h");
                    int st_low = b.getInt("RR_start_time_l");
                    rr_processor.start_time = (st_high<<24) + st_low;

                    rr_processor.RR_cur_hist = b.getFloatArray("RR_cur");
                    rr_processor.RR_prev_hist = b.getFloatArray("RR_prev");

                    rr_processor.hrv_parameter = b.getFloat("HRV_parameter");

                    ecg_processor.hist_depth = b.getInt("ECG_count");
                    ecg_processor.points_hist = b.getFloatArray("ECG_data");
                    ecg_processor.points_valid = b.getIntArray("ECG valid");
                    int[] uids_high = b.getIntArray("ECG uid_h");
                    int[] uids_low = b.getIntArray("ECG uid_l");
                    for(int x = 0; x < ecg_processor.hist_depth; x++) {
                        ecg_processor.points_uid[x] = uids_low[x] + (uids_high[x]<<24);
                    }

                    bpm_processor.restore_from_arrays(b.getIntArray("bpm_processor_int"), b.getFloatArray("bpm_processor_float"));

                    ping_pending = 0;
                }
                if(sig == ble_uecg_service.UECG_FW_UPLOAD_RESPONSE)
                {
                    Bundle b = intent.getBundleExtra("FW_upload_progress");
                    int progress = b.getInt("progress");
                    if(progress < 0) {
                        lbl_fw_upload_status.text_size = 45;
                        lbl_fw_upload_status.text = "Scanning...";
                    }
                    else if(progress == 0) {
                        lbl_fw_upload_status.text_size = 45;
                        lbl_fw_upload_status.text = "Device found, starting upload...";
                    }
                    else if(progress < 200) {
                        lbl_fw_upload_status.text_size = 55;
                        lbl_fw_upload_status.text = progress + "% complete";
                        pb_fw_upload_progress.progress_percent = progress;
                    }
                    else {
                        lbl_fw_upload_status.text_size = 45;
                        lbl_fw_upload_status.text = "Upload completed successfully!";
                        pb_fw_upload_progress.progress_percent = 100;
                    }
                }

            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("uECG_DATA");
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        registerReceiver(uecg_data_receiver, filter);

        IntentFilter filter_ecg = new IntentFilter();
        filter_ecg.addAction("uECG_ECG");
        filter_ecg.addCategory(Intent.CATEGORY_DEFAULT);
        registerReceiver(data_receiver_ecg, filter_ecg);

        IntentFilter filter_hrv = new IntentFilter();
        filter_hrv.addAction("uECG_HRV");
        filter_hrv.addCategory(Intent.CATEGORY_DEFAULT);
        registerReceiver(data_receiver_hrv, filter_hrv);

        IntentFilter filter_imu_rr = new IntentFilter();
        filter_imu_rr.addAction("uECG_IMU_RR");
        filter_imu_rr.addCategory(Intent.CATEGORY_DEFAULT);
        registerReceiver(data_receiver_imu_rr, filter_imu_rr);

        IntentFilter filter_s = new IntentFilter();
        filter_s.addAction("uECG_SIGNAL");
        filter_s.addCategory(Intent.CATEGORY_DEFAULT);
        registerReceiver(ecg_service_info, filter_s);

        create_buttons();
        SurfaceView sv = (SurfaceView)findViewById(R.id.surfaceView);
        sv.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                return touch_process(event);
            }
        });

        Log.e("uECG activity", "starting service");

        Intent intent = new Intent("uECG_SERVICE_SIGNAL");
        intent.putExtra("signal", ble_uecg_service.UECG_STATE_ARRAY_REQUEST);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        sendBroadcast(intent);

        ping_request_sent = System.currentTimeMillis();
        ping_pending = 11;

        if (ContextCompat.checkSelfPermission(MainActivity.this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED){
            if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this,
                    Manifest.permission.ACCESS_FINE_LOCATION)){
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }else{
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }
        }
        timer = new Timer();
        timer.schedule(new drawUITask(), 100,100);

    }

    public static boolean is_location_enabled(Context context) {
        int locationMode = 0;

        try {
            locationMode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE);

        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
            return false;
        }

        return locationMode != Settings.Secure.LOCATION_MODE_OFF;
    }
}
