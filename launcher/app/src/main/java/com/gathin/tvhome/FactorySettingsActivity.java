package com.gathin.tvhome;
/** Open the television vendor's settings without replacing its components. */
public class FactorySettingsActivity extends android.app.Activity {
 @Override protected void onCreate(android.os.Bundle state){super.onCreate(state);
  try{startActivity(SystemSettings.intent(this,android.provider.Settings.ACTION_SETTINGS));}
  catch(android.content.ActivityNotFoundException|SecurityException e){android.widget.Toast.makeText(this,"系统未提供可打开的原厂设置入口",android.widget.Toast.LENGTH_LONG).show();}
  finish();
 }
}
