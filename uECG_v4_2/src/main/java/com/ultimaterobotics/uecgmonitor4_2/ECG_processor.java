package com.ultimaterobotics.uecgmonitor4_2;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.text.SimpleDateFormat;
import java.util.Calendar;

public class ECG_processor {
    //processing buffer
    float[] points_hist = new float[500];
    long[] points_uid = new long[500];
    int[] points_valid = new int[500];
    int hist_depth = 500;

    //drawing buffer - when in pause mode, not updated with processing buffer
    float[] draw_points_buf = new float[20000];
    long[] draw_points_uid = new long[20000];
    int[] draw_points_valid = new int[20000];
    int draw_buf_size = 20000;
    int draw_buf_cur_pos = 0;

    int draw_grid = 1;

    long save_start_time = 0;

    float data_rate = 122; //uECG sends at 122 Hz and for now it's a constant for BLE version

    float screen_time = 2.5f; //time interval currently displayed on screen

    int paused = 0;

    float vx, vy, vw, vh;

    int img_width;
    int img_height;

    float draw_shift = 0;
    public ECG_processor()
    {
        ;
    }

    public void set_save_time(long value)
    {
        save_start_time = value;
    }

    public void set_pause_state(int pause_on)
    {
        paused = pause_on;
    }

    public void set_draw_shift(float dt)
    {
        draw_shift = dt;
    }

    public void set_screen_time(float val)
    {
        screen_time = val;
    }

    public void set_viewport(float pos_x, float pos_y, float width, float height)
    {
        vx = pos_x;
        vy = pos_y;
        vw = width;
        vh = height;
    }

    public void push_data(int d_id, int[] values, int values_count, int mark_data)
    {
        for(int x = 0; x < hist_depth - d_id; x++)
        {
            points_hist[x] = points_hist[x + d_id];
            points_uid[x] = points_uid[x + d_id];
            points_valid[x] = points_valid[x + d_id];
        }
        long tm = System.currentTimeMillis();
        for(int x = 0; x < d_id; x++)
        {
            points_hist[hist_depth - d_id + x] = values[0];
            points_uid[hist_depth - d_id + x] = tm;
            points_valid[hist_depth - d_id + x] = 0;
            if(mark_data != 0)
                points_valid[hist_depth - d_id + x] = 3;
        }
        for(int x = 0; x < values_count; x++)
        {
            points_hist[hist_depth - values_count + x] = values[x];
            points_uid[hist_depth - values_count+ x] = tm;
            if(mark_data == 0)
                points_valid[hist_depth - values_count + x] = 1;
            else
                points_valid[hist_depth - values_count + x] = mark_data;
        }

        if(paused != 0) return;

        for(int x = 0; x < d_id; x++)
        {
            draw_points_buf[draw_buf_cur_pos] = points_hist[hist_depth - d_id + x];
            draw_points_uid[draw_buf_cur_pos] = points_uid[hist_depth - d_id + x];
            draw_points_valid[draw_buf_cur_pos] = points_valid[hist_depth - d_id + x];
            draw_buf_cur_pos++;
            if(draw_buf_cur_pos >= draw_buf_size)
                draw_buf_cur_pos = 0;
        }
    }

    public int get_last_data_count(int count, float[] data, long[] uid, int[] valid)
    {
        int cnt = count;
        if(count > hist_depth-2) cnt = hist_depth-2;
        for(int x = 0; x < cnt; x++)
        {
            data[x] = points_hist[hist_depth - cnt + x];
            uid[x] = points_uid[hist_depth - cnt + x];
            valid[x] = points_valid[hist_depth - cnt + x];
        }
        return cnt;
    }

    int time_to_x(float tim)
    {
        int right = (int)((vx + vw) * img_width);
        float scr_per_second = vw / screen_time;
        return right - (int)(scr_per_second * tim * img_width);
    }

    public void draw(Canvas img)
    {
        Paint pnt = new Paint();
        int width = img.getWidth();
        int height = img.getHeight();
        img_width = width;
        img_height = height;

        int left = (int)(vx * width);
        int top = (int)(vy * height);
        int right = (int)((vx + vw) * width);
        int bottom = (int)((vy + vh) * height);
        int center = (top + bottom) / 2;
        int length = right - left;

        int buf_pos = draw_buf_cur_pos - 1;
        if(paused > 0 && draw_shift > 0)
        {
            buf_pos = draw_buf_cur_pos - (int)(data_rate * draw_shift);
        }
        if(buf_pos < 0) buf_pos += draw_buf_size;

        int prev_x = right;
        int prev_y = center;
        int num_points = (int)(data_rate * screen_time);
        int ppos = buf_pos;
        float min_v = draw_points_buf[ppos];
        float max_v = min_v;
        float avg_val = 0;
        float avg_z = 0.00001f;
        min_v -= 10;
        max_v += 10;

        for(int p = 0; p < num_points; p++)
        {
            ppos = buf_pos - p;
            if(ppos < 0) ppos += draw_buf_size;
            float p_val = draw_points_buf[ppos];
            if(p_val < min_v) min_v = p_val;
            if(p_val > max_v) max_v = p_val;
            avg_val += p_val;
            avg_z += 1.0f;
        }

        avg_val /= avg_z;
        if(draw_grid > 0)
        {
            pnt.setARGB(255, 120, 80, 80);
            float tim_step = 0.04f;
            int cnt = 0;
            for(float tv = 0; tv < screen_time; tv += tim_step)
            {
                if(cnt%5 == 0)
                    pnt.setARGB(255, 80, 120, 150);
                else
                    pnt.setARGB(120, 80, 120, 150);
                int x_pos = time_to_x(tv);
                if(screen_time < 3.0 || cnt%5 == 0)
                    img.drawLine(x_pos, bottom, x_pos, top, pnt);
                cnt++;
            }
//0.57220459 uV per 1 unit
            float min_uv = (avg_val - min_v) * 0.5722f; //in uV
            float max_uv = (max_v - avg_val) * 0.5722f; //in uV

            cnt = 0;
            for(float uv = 0; uv < max_uv; uv += 100)
            {
                if(cnt%5 == 0)
                    pnt.setARGB(255, 80, 120, 150);
                else
                    pnt.setARGB(120, 80, 120, 150);
                int y_pos = bottom + (int)((top - bottom) * (avg_val + uv / 0.5722f - min_v) / (max_v - min_v));
                img.drawLine(left, y_pos, right, y_pos, pnt);
                cnt++;
            }
            cnt = 0;
            for(float uv = 0; uv < min_uv; uv += 100)
            {
                if(cnt%5 == 0)
                    pnt.setARGB(255, 80, 120, 150);
                else
                    pnt.setARGB(120, 80, 120, 150);
                int y_pos = bottom + (int)((top - bottom) * (avg_val - uv / 0.5722 - min_v) / (max_v - min_v));
                img.drawLine(left, y_pos, right, y_pos, pnt);
                cnt++;
            }
        }

        pnt.setStrokeWidth(3.0f);
        for(int p = 0; p < num_points; p++)
        {
            ppos = buf_pos - p;
            if(ppos < 0) ppos += draw_buf_size;
            float p_val = draw_points_buf[ppos];
            int valid = draw_points_valid[ppos];
            int cur_x = right - (p * length / num_points);
            int cur_y = bottom + (int)((top - bottom) * (p_val - min_v) / (max_v - min_v));

            if(p > 0)
            {
                if(valid == 0)
                    pnt.setARGB(255, 250, 155, 70);
                else
                    pnt.setARGB(255, 50, 255, 70);

                img.drawLine(prev_x, prev_y, cur_x, cur_y, pnt);
            }
            prev_x = cur_x;
            prev_y = cur_y;
        }


    }


    public void draw_spanshot(Canvas img, int period_s, int data_period_s, int header_pdf, int BPM, int RMSSD, int RR_min, int RR_max, int use_mark)
    {
        Paint pnt = new Paint();
        int width = img.getWidth();
        int height = img.getHeight();
        int DX = 0;
        int DY = 110;

        int sec_per_line = 5; //seconds in one image line
        int lines = period_s / sec_per_line + 1;
        int endfields = 10;
        float uv_per_line = 3000;
        int line_height = (height - DY) / lines;

        if(true) {
            int buf_pos;
            buf_pos = draw_buf_cur_pos - 1 - (int) (data_rate * data_period_s);

            if (buf_pos < 0) buf_pos += draw_buf_size;
            if (buf_pos < 0) buf_pos = draw_buf_cur_pos; //max possible depth reached

            int num_points = (int) (data_rate * period_s);
            int ppos = buf_pos;

            float min_v = draw_points_buf[ppos];
            float max_v = min_v;
            float avg_val = 0;
            float avg_z = 0.00001f;
            min_v -= 10;
            max_v += 10;

            for (int p = 0; p < num_points; p++) {
                ppos = buf_pos + p;
                if (ppos >= draw_buf_size) ppos -= draw_buf_size;
                float p_val = draw_points_buf[ppos];
                if (p_val < min_v) min_v = p_val;
                if (p_val > max_v) max_v = p_val;
                avg_val += p_val;
                avg_z += 1.0f;
            }
            uv_per_line = (max_v - min_v)*1.05f;
            if(uv_per_line > 3000) uv_per_line = 3000;
        }

        pnt.setARGB(255, 250, 220, 220); //pink
        img.drawRect(0, 0, width, height, pnt); //background

        if(use_mark > 0)
        {
            pnt.setTextSize(26);
            pnt.setARGB(255, 200, 0, 220);
            img.drawRect(width-100, 0, width, DY, pnt); //background
            pnt.setARGB(255, 255, 255, 255);
            img.drawText("M" + use_mark, width - 80, 80, pnt);
        }

        pnt.setARGB(255, 0, 0, 0); //grey
        pnt.setStrokeWidth(2.0f);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy MMM dd, HH:mm:ss");
        String ts = sdf.format(Calendar.getInstance().getTime());
        if(header_pdf > 0)
        {
            pnt.setTextSize(26);
            img.drawText("recorded by uECG at " + ts + ", single lead, 5 seconds per line", 10, 50, pnt);
//            img.drawText("BPM: " + BPM + " RMSSD(5 min): " + RMSSD + " RR_min: " + RR_min + " RR_max: " + RR_max, 10, 80, pnt);
            img.drawText("BPM: " + BPM + "  RMSSD(5 min): " + RMSSD, 10, 80, pnt); //RR min/max are not reliable enough
        }
        else {
            pnt.setTextSize(30);
            img.drawText("Snapshot from uECG device at " + ts, 100, 50, pnt);
            img.drawText(period_s + " seconds single lead (5 seconds per line), time grid 0.2s", 50, 80, pnt);
        }

        pnt.setARGB(255, 50, 50, 50); //grey
        pnt.setStrokeWidth(2.0f);

        float tim_step = 0.2f;
        int cnt = 0;
        for(float tv = 0; tv < sec_per_line; tv += tim_step)
        {
            int x_pos = (int)(tv / (float)sec_per_line * (float)(width - 2*endfields));
            img.drawLine(DX+x_pos, DY, DX+x_pos, height, pnt);
        }
        float max_uv = (lines-1)*uv_per_line;
        for(float uv = 0; uv < max_uv; uv += 200)
        {
            if(cnt%5 == 0)
                pnt.setARGB(255, 0, 0, 0);
            else
                pnt.setARGB(120, 50, 50, 50);
            int y_pos = (int)(height * uv / max_uv);
            img.drawLine(0, DY+y_pos, width, DY+y_pos, pnt);
            cnt++;
        }

        pnt.setStrokeCap(Paint.Cap.ROUND);

        for(int l = 0; l < lines; l++) {
            int buf_pos;
            buf_pos = draw_buf_cur_pos - 1 - (int) (data_rate * (data_period_s - l * sec_per_line));
            if(data_period_s - l * sec_per_line < 0) buf_pos = draw_buf_cur_pos - 1;

            if (buf_pos < 0) buf_pos += draw_buf_size;
            if (buf_pos < 0) buf_pos = draw_buf_cur_pos; //max possible depth reached

            int left = endfields;
            int right = width - endfields;
            int center = (int) ((float) (height - DY) / (float) lines * (l + 0.5f));
            int length = right - left;

            int prev_x = 0;
            int prev_y = center;
            int num_points = (int) (data_rate * sec_per_line);
            int ppos = buf_pos;

            float min_v = draw_points_buf[ppos];
            float max_v = min_v;
            float avg_val = 0;
            float avg_z = 0.00001f;
            min_v -= 10;
            max_v += 10;

            for (int p = 0; p < num_points; p++) {
                ppos = buf_pos + p;
                if (ppos >= draw_buf_size) ppos -= draw_buf_size;
                float p_val = draw_points_buf[ppos];
                if (p_val < min_v) min_v = p_val;
                if (p_val > max_v) max_v = p_val;
                avg_val += p_val;
                avg_z += 1.0f;
            }

            avg_val /= avg_z;

            pnt.setStrokeWidth(2.5f);
            for (int p = 0; p < num_points; p++) {
                ppos = buf_pos + p;
                if (ppos >= draw_buf_size) ppos -= draw_buf_size;
                float p_val = draw_points_buf[ppos];
                int valid = draw_points_valid[ppos];
                if(data_period_s - l * sec_per_line < 0) p_val = avg_val;
                int cur_x = left + (p * length / num_points);
                int cur_y = center - (int) (line_height * (p_val - avg_val) * 1.1444f / uv_per_line); //1.1444 uV per ADC count

                if(cur_y > center + 2*line_height) cur_y = center + 2*line_height;
                if(cur_y < center - 2*line_height) cur_y = center - 2*line_height;
                if (p > 0) {
                    if (valid == 0)
                        pnt.setARGB(255, 150, 50, 50);
                    else
                        pnt.setARGB(255, 20, 20, 20);

                    img.drawLine(DX+prev_x, DY+prev_y, DX+cur_x, DY+cur_y, pnt);
                }
                prev_x = cur_x;
                prev_y = cur_y;
            }
        }


    }

}
