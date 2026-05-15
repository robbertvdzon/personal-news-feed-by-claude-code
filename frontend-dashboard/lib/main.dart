import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'providers/auth_provider.dart';
import 'screens/login_screen.dart';
import 'screens/home_screen.dart';
import 'theme.dart';

void main() {
  runApp(const ProviderScope(child: DashboardApp()));
}

class DashboardApp extends ConsumerStatefulWidget {
  const DashboardApp({super.key});
  @override
  ConsumerState<DashboardApp> createState() => _DashboardAppState();
}

class _DashboardAppState extends ConsumerState<DashboardApp> {
  @override
  void initState() {
    super.initState();
    // Bootstrap auth uit SharedPreferences zodat ingelogde users niet
    // elke keer opnieuw hoeven in te loggen.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(authProvider.notifier).bootstrap();
    });
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(authProvider);
    return MaterialApp(
      title: 'PNF Dashboard',
      debugShowCheckedModeBanner: false,
      // Altijd light — past bij de witte stijl van de PNF-feed-app.
      themeMode: ThemeMode.light,
      theme: AppTheme.light(),
      home: auth.isAuthenticated ? const HomeShell() : const LoginScreen(),
    );
  }
}
