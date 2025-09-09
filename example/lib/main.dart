import 'package:flutter/material.dart';
import 'package:ivs_broadcaster_example/home_page.dart';

import 'player_page.dart';

const String playBackUrl =
    "https://7453a0e95db4.us-east-1.playback.live-video.net/api/video/v1/us-east-1.655758237974.channel.TkU9oEEBXbzE.m3u8";
void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);

    switch (state) {
      case AppLifecycleState.resumed:
        print('🟢 App State: RESUMED - App is in foreground and interactive');
        break;
      case AppLifecycleState.inactive:
        print(
            '🟡 App State: INACTIVE - App is in foreground but not interactive');
        break;
      case AppLifecycleState.paused:
        print('🔴 App State: PAUSED - App is in background');
        break;
      case AppLifecycleState.detached:
        print('⚫ App State: DETACHED - App is being shut down');
        break;
      case AppLifecycleState.hidden:
        print('🔵 App State: HIDDEN - App is hidden');
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      home: BroadCastWidget(),
    );
  }
}

class BroadCastWidget extends StatefulWidget {
  const BroadCastWidget({
    super.key,
  });

  @override
  State<BroadCastWidget> createState() => _BroadCastWidgetState();
}

class _BroadCastWidgetState extends State<BroadCastWidget> {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Row(),
            ElevatedButton(
              onPressed: () async {
                Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => const HomePage(),
                  ),
                );
              },
              child: const Text('Start Broadcast'),
            ),
            ElevatedButton(
              onPressed: () async {
                Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => const PlayerPage(),
                  ),
                );
              },
              child: const Text('Start Player'),
            ),
          ],
        ),
      ),
    );
  }
}
