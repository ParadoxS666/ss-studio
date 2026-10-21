package android.content;
public interface SharedPreferences {
  interface Editor { Editor putInt(String k,int v); Editor putBoolean(String k,boolean v); Editor putFloat(String k,float v); Editor putString(String k,String v); void apply(); }
  int getInt(String k,int d); boolean getBoolean(String k,boolean d); float getFloat(String k,float d); String getString(String k,String d); boolean contains(String k); Editor edit();
}
