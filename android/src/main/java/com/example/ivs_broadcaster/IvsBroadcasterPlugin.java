package com.example.ivs_broadcaster;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

public class IvsBroadcasterPlugin implements FlutterPlugin, ActivityAware {

  private static final String TAG = "IvsBroadcasterPlugin";

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    Log.d(TAG, "🔌 Plugin attached to engine");
    binding
        .getPlatformViewRegistry()
        .registerViewFactory(
            "ivs_broadcaster", new StreamFactory(binding.getBinaryMessenger()));
    binding.getPlatformViewRegistry().registerViewFactory("ivs_player", new PlayerViewFactory(binding.getBinaryMessenger()));
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    Log.d(TAG, "🔌 Plugin detached from engine");
  }

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    Log.d(TAG, "🏠 Plugin attached to activity - App is ready");
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    Log.d(TAG, "🔄 Plugin detached from activity for config changes");
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    Log.d(TAG, "🔄 Plugin reattached to activity after config changes");
  }

  @Override
  public void onDetachedFromActivity() {
    Log.d(TAG, "🏠 Plugin detached from activity - App is closing");
  }
}
