package tk.newk.battery;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.os.BatteryManager;
import android.os.IBinder;

import android.preference.PreferenceManager;

import android.support.v4.app.TaskStackBuilder;

import static tk.newk.common.log.*;
import static tk.newk.common.utils.*;

import tk.newk.battery.Common;
import static tk.newk.battery.Common.*;

import android.support.v4.app.NotificationCompat;

public class MonitorService extends Service
{

  void load_recently_history()
  {
    InputStream is = null;
    byte[] buf = new byte[RECORD_LENGTH];
    buffer_data_used_size = 0;
    try
    {
      is = new BufferedInputStream(openFileInput(FILE_NAME_RECENT));
      int l = 0;
      do
      {
        l = is.read(buf, 0, RECORD_LENGTH);
        if (l > 0)
        {
          Common.buffer_data[buffer_data_used_size] = new BatteryInfo(buf);
          ++buffer_data_used_size;
        }
      }while (l == RECORD_LENGTH);
      if (is != null)
        is.close();
    }
    catch (Exception e)
    {
      logexception(this, e);
    }
  }

  @Override
  public void onCreate() 
  {
    log_set_tag("battery");
    load_recently_history();
    TTS_helper_class = new TTS_helper(this);

    if (global_setting == null)
      global_setting = PreferenceManager.getDefaultSharedPreferences(this);
    service_context = this;
    if (update_battery_used_rate_list())
      adapter_battery_used_rate.notifyDataSetChanged();
    logv(this, "service is created");
  }

  boolean copy_file(String from_file, String to_file)
  {
    InputStream is = null;
    FileOutputStream fos = null;
    byte[] buf = new byte[1024];
    int read_len;
    try
    {
      is = new BufferedInputStream(openFileInput(from_file));
      fos = openFileOutput(to_file, Context.MODE_PRIVATE);
      do
      {
        read_len = is.read(buf, 0, 1024);
        if (read_len > 0)
          fos.write(buf, 0, read_len);
      }while(read_len > 0);
      is.close();
      fos.close();
      return true;
    }
    catch (Exception e)
    {
      logexception(this, e);
      return false;
    }

  }

  void save_history(BatteryInfo bi)
  {
    logd(this, "saving batter info", str(buffer_data_used_size));
    buffer_data[buffer_data_used_size] = bi;
    FileOutputStream fos = null;
    try
    {
      fos = openFileOutput(FILE_NAME_RECENT, Context.MODE_APPEND);
      fos.write(bi.to_bytes());
      fos.close();
      ++buffer_data_used_size;
    }
    catch(Exception e)
    {
      logexception(this, e);
    }
    //when the next save action to recent file will reach the max size of history file, we must transfer the recent file to history file
    if (buffer_data_used_size >= HISTORY_FILE_RECORD_COUNT)
    {
      logd(this, "start to copy recent file to history");
      latest_history_index = find_latest_history_index();
      String file_name = String.format(FILE_NAME_HISTORY, 
          latest_history_index + 1);
      logd(this, "target file name", file_name);
      if (copy_file(FILE_NAME_RECENT, file_name))
        ++latest_history_index;
      //delete recent file and clear buffer data
      deleteFile(FILE_NAME_RECENT);
      //don't need to actually delete the buffer data, only change index is OK
      //if want to reduce memory used, you can assign all elements to null,
      //but I think is not necessary because the buffer_data array is not so big
      buffer_data_used_size = 0;
    }
  }

  void update_current_battery_info(Intent intent)
  {
    int h = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 
        BatteryManager.BATTERY_HEALTH_UNKNOWN);
    String health = "";
    switch (h)
    {
      case BatteryManager.BATTERY_HEALTH_UNKNOWN:
        health = getString(R.string.str_battery_health_unknown);
        break;
      case BatteryManager.BATTERY_HEALTH_COLD:
        health = getString(R.string.str_battery_health_cold);
        break;
      case BatteryManager.BATTERY_HEALTH_DEAD:
        health = getString(R.string.str_battery_health_dead);
        break;
      case BatteryManager.BATTERY_HEALTH_GOOD:
        health = getString(R.string.str_battery_health_good);
        break;
      case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
        health = getString(R.string.str_battery_health_over_voltage);
        break;
      case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
        health = getString(R.string.str_battery_health_error);
        break;
      case BatteryManager.BATTERY_HEALTH_OVERHEAT:
        health = getString(R.string.str_battery_health_over_heat);
        break;
    }
    float temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
    float voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f;
    str_current_battery_state = 
      String.format(getString(R.string.str_current_battery_state),
        intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), 
        voltage, temp, health);
    if (lbl_current_battery_state != null)
    {
      lbl_current_battery_state.setText(str_current_battery_state);
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int start_id)
  {
    logv(this, "service is start");
    if (intent != null && intent.getBooleanExtra(
          Common.START_SERVICE_FROM_RECEIVER, false))
    {
      update_current_battery_info(intent);
      BatteryInfo bi = new BatteryInfo(intent);
      logd(this, bi.toString());
      if (!is_notification_init)
      {
        update_notification(bi);
        if (!is_service_foreground && m_builder != null)
        {
          this.startForeground(ID_NOTIFICATION, m_builder.build());
          is_service_foreground = true;
        }
        is_notification_init = true;
      }
      if (buffer_data != null && buffer_data_used_size > 0 
          && bi.level == buffer_data[buffer_data_used_size - 1].level)
      {
        logd(this, "battery level no change, ignored");
      }
      else
      {
        save_history(bi);
        if (update_battery_used_rate_list())
          adapter_battery_used_rate.notifyDataSetChanged();
        logv(this, "start to update notification");
        update_notification(bi);
        if (/*!is_service_foreground && */m_builder != null)
        {
          this.startForeground(ID_NOTIFICATION, m_builder.build());
          is_service_foreground = true;
        }
      }
    }
    if (!is_receiver_registed)
    {
      logd(this, "registing receiver");
      this.registerReceiver(battery_changed_receiver, 
          new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      is_receiver_registed = true;
    }
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent)
  {
    return null;
  }

  @Override
  public void onDestroy()
  {
    if (is_receiver_registed)
    {
      unregisterReceiver(battery_changed_receiver);
      is_receiver_registed = false;
    }
    stopForeground(false);
    is_service_foreground = false;
    NotificationManager nm = (NotificationManager)getSystemService(
        Context.NOTIFICATION_SERVICE);
    nm.cancelAll();
    is_notification_init = false;
    logw(this, "service is Destroy");
  }

  private NotificationCompat.Builder m_builder;

  void update_notification(BatteryInfo bi)
  {
    if (m_builder == null)
    {
      m_builder = new NotificationCompat.Builder(this);
      Intent open_intent = new Intent(this, mainActivity.class);
      TaskStackBuilder stack_builder = TaskStackBuilder.create(this);
      stack_builder.addParentStack(mainActivity.class);
      stack_builder.addNextIntent(open_intent);
      PendingIntent pending_intent = stack_builder.getPendingIntent(
          0,
          PendingIntent.FLAG_UPDATE_CURRENT);
      m_builder.setContentIntent(pending_intent);
    }
    if (bi != null)
    {
      //Hint by newk
      //I want to use LevelListDrawable to change icon of the notification
      //but for unknown reason, the large icon will not change with the 
      //`setSmallIcon(int, int)`, (FYI, `setSmallIcon(int)` is OK)
      //
      //so I need to use `setLargeIcon(Bitmap)` to change the large icon
      //and then there is a side effect, 
      //in Android4.1, there will be an additional small icon 
      //show in the right-bottom corner of the notification
      //in Android2.3, the large icon will not change
      //
      //so at last, I give up to use LevelListDrawable, I calulate the 
      //id of icons manually
      
//      Drawable icon_list = getResources().getDrawable(R.drawable.icons);
//      icon_list.setLevel(bi.level);
      m_builder
//          .setSmallIcon(R.drawable.icons, bi.level)
//          .setLargeIcon(((BitmapDrawable)icon_list.getCurrent()).getBitmap())
          .setSmallIcon(R.drawable.level_001 + bi.level - 1)
          .setContentTitle(String.format(getString(R.string.str_notification_content_title), bi.level))
          .setContentText(getString(R.string.str_notification_content_text))
          .setOngoing(true);
      NotificationManager nm = (NotificationManager)getSystemService(
          Context.NOTIFICATION_SERVICE);
      //Hint: this way also make no effect on the large icon
//      Notification n = m_builder.build();
//      n.icon = R.drawable.icons;
//      n.iconLevel = bi.level;
//      nm.notify(ID_NOTIFICATION, n);
      nm.notify(ID_NOTIFICATION, m_builder.build());
      TTS_show_level(bi.level);
    }
  }

  void TTS_show_level(int level)
  {
    if (can_speak())
    {
      if (read_setting_boolean(PREF_KEY_TTS_LOW_ALARM, true)
          && power_supply_state != POWER_SUPPLY_STATE_CONNECTED
          && (level == 25 || level == 23 || level == 21 || level == 19
              || level == 17))
      {
        TTS_helper_class.say("battery level is low, please charge", 1.0f);
      }
      if (read_setting_boolean(PREF_KEY_TTS_VERY_LOW_ALARM, true)
          && power_supply_state != POWER_SUPPLY_STATE_CONNECTED
          && (level == 15 || level == 13 || level == 11 || level == 9
              || level == 7 || level == 5 || level == 3))
      {
        TTS_helper_class.say(
            "battery level is very low, please charge immediately", 1.0f);
      }
      if (read_setting_boolean(PREF_KEY_TTS_CHARGE_PROGRESS, true)
          && power_supply_state == POWER_SUPPLY_STATE_CONNECTED
          && (level == 50 || level == 60 || level == 70 || level == 80
              || level == 90 || level == 95 || level == 99))
      {
        logv(this, "start to say");
        TTS_helper_class.say(
            String.format("battery level is %d percent", level), 0.5f);
      }
      if (read_setting_boolean(PREF_KEY_TTS_USED_PROGRESS, true)
          && power_supply_state != POWER_SUPPLY_STATE_CONNECTED
          && (level == 50 || level == 40 || level == 30))
      {
        TTS_helper_class.say(
            String.format("battery level is %d percent", level), 0.5f);
      }
      if (read_setting_boolean(PREF_KEY_TTS_CHARGE_PROGRESS, true)
          && power_supply_state == POWER_SUPPLY_STATE_CONNECTED
          && (level == 100))
      {
        TTS_helper_class.say("I am full", 0.5f);
      }
    }
  }
}

// vim: fdm=syntax fdl=1 fdn=2

