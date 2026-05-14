import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'providers/auth_provider.dart';
import 'providers/data_providers.dart';
import 'providers/version_provider.dart';
import 'screens/login_screen.dart';
import 'screens/main_shell.dart';
import 'util/hard_reload.dart';

void main() {
  runApp(const ProviderScope(child: NewsFeedApp()));
}

class NewsFeedApp extends ConsumerStatefulWidget {
  const NewsFeedApp({super.key});

  @override
  ConsumerState<NewsFeedApp> createState() => _NewsFeedAppState();
}

class _NewsFeedAppState extends ConsumerState<NewsFeedApp>
    with WidgetsBindingObserver {
  bool _booted = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    Future.microtask(() async {
      await ref.read(authProvider.notifier).bootstrap();
      // Eerste versie-check direct bij app-start.
      await ref.read(versionProvider.notifier).check();
      setState(() => _booted = true);
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Op web wordt `resumed` getriggerd bij window-focus, op mobiel bij
    // terugkeer uit de achtergrond. In beide gevallen willen we even
    // checken of er een nieuwe deploy beschikbaar is.
    if (state == AppLifecycleState.resumed) {
      ref.read(versionProvider.notifier).check();
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(authProvider);
    final appearance = ref.watch(appearanceProvider);
    return MaterialApp(
      title: 'Personal News Feed',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
        scaffoldBackgroundColor: Colors.red.shade100,
      ),
      builder: (context, child) {
        final base = MediaQuery.of(context);
        return MediaQuery(
          data: base.copyWith(
            textScaler: TextScaler.linear(appearance.largeFont ? 1.38 : 1.0),
          ),
          child: Stack(children: [
            child ?? const SizedBox.shrink(),
            const Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: _UpdateAvailableBanner(),
            ),
          ]),
        );
      },
      home: !_booted
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : auth.isLoggedIn
              ? const MainShell()
              : const LoginScreen(),
    );
  }
}

/// Persistente snackbar onderin: blijft staan tot de gebruiker op
/// "Nu vernieuwen" tikt (of zelf herlaadt). Geen timeout — anders kan de
/// gebruiker hem op een mobiel scherm makkelijk missen.
class _UpdateAvailableBanner extends ConsumerWidget {
  const _UpdateAvailableBanner();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final v = ref.watch(versionProvider);
    if (!v.updateAvailable) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final bottomInset = MediaQuery.of(context).padding.bottom;
    return SafeArea(
      top: false,
      child: Padding(
        padding: EdgeInsets.fromLTRB(12, 0, 12, 12 + bottomInset),
        child: Material(
          elevation: 6,
          borderRadius: BorderRadius.circular(8),
          color: theme.colorScheme.inverseSurface,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
            child: Row(children: [
              Expanded(
                child: Text(
                  'Er is een nieuwe versie beschikbaar.',
                  style: TextStyle(color: theme.colorScheme.onInverseSurface),
                ),
              ),
              TextButton(
                onPressed: () => hardReload(),
                style: TextButton.styleFrom(
                  foregroundColor: theme.colorScheme.inversePrimary,
                ),
                child: const Text('Nu vernieuwen'),
              ),
            ]),
          ),
        ),
      ),
    );
  }
}
