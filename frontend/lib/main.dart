import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'providers/auth_provider.dart';
import 'providers/data_providers.dart';
import 'screens/login_screen.dart';
import 'screens/main_shell.dart';

void main() {
  runApp(const ProviderScope(child: NewsFeedApp()));
}

class NewsFeedApp extends ConsumerStatefulWidget {
  const NewsFeedApp({super.key});

  @override
  ConsumerState<NewsFeedApp> createState() => _NewsFeedAppState();
}

class _NewsFeedAppState extends ConsumerState<NewsFeedApp> {
  bool _booted = false;

  @override
  void initState() {
    super.initState();
    Future.microtask(() async {
      await ref.read(authProvider.notifier).bootstrap();
      setState(() => _booted = true);
    });
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
      ),
      builder: (context, child) {
        final base = MediaQuery.of(context);
        return MediaQuery(
          data: base.copyWith(
            textScaler: TextScaler.linear(appearance.largeFont ? 1.38 : 1.0),
          ),
          child: child ?? const SizedBox.shrink(),
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
