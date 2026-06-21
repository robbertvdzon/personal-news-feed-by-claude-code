import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'providers/auth_provider.dart';
import 'providers/data_providers.dart';
import 'providers/version_provider.dart';
import 'screens/login_screen.dart';
import 'screens/main_shell.dart';
import 'screens/feed_detail_screen.dart';
import 'screens/rss_detail_screen.dart';
import 'util/deep_link.dart';
import 'util/hard_reload.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Zet de path-URL-strategie aan en lees een eventueel item-pad
  // (/feed/<id> of /rss/<id>) in [pendingDeepLink].
  initDeepLinks();
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

  // Deeplink-afhandeling zit hier (de stabiele root) i.p.v. in MainShell:
  // tijdens de boot wordt de spinner→MainShell-transitie (en de URL-reset
  // van /feed/<id> naar /) door Flutter's interne Router afgehandeld, wat
  // MainShell kan disposen+remounten — een push vanuit MainShell.initState
  // gaat dan verloren. Vanaf de root, via een eigen navigatorKey en ná de
  // boot-settle, overleeft de push.
  final GlobalKey<NavigatorState> _navKey = GlobalKey<NavigatorState>();
  final GlobalKey<ScaffoldMessengerState> _msgKey =
      GlobalKey<ScaffoldMessengerState>();
  bool _deepLinkHandled = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    Future.microtask(() async {
      await ref.read(authProvider.notifier).bootstrap();
      // Eerste versie-check direct bij app-start.
      await ref.read(versionProvider.notifier).check();
      setState(() => _booted = true);
      _maybeOpenDeepLink();
    });
  }

  /// Open een bookmark-URL (`/feed/<id>` of `/rss/<id>`) als los item, één
  /// keer, zodra de app geboot en ingelogd is.
  void _maybeOpenDeepLink() {
    if (_deepLinkHandled || !_booted) return;
    if (!ref.read(authProvider).isLoggedIn) return; // wacht tot na login
    final link = pendingDeepLink;
    _deepLinkHandled = true;
    pendingDeepLink = null;
    if (link == null) return;
    // Via een microtask (niet addPostFrameCallback — die vuurt tijdens de
    // boot niet betrouwbaar) op de root-navigator, zodat de push de
    // spinner→MainShell-transitie overleeft.
    Future(() => _openDeepLink(link));
  }

  Future<void> _openDeepLink(DeepLink link) async {
    final nav = _navKey.currentState;
    if (nav == null) return;
    try {
      if (link.type == 'rss') {
        final items = await ref.read(rssProvider.future);
        final i = items.indexWhere((e) => e.id == link.id);
        if (i < 0) return _notAvailable();
        nav.push(MaterialPageRoute(
          builder: (_) => RssItemDetailScreen(items: [items[i]], initialIndex: 0),
        ));
      } else {
        final items = await ref.read(feedProvider.future);
        final i = items.indexWhere((e) => e.id == link.id);
        if (i < 0) return _notAvailable();
        nav.push(MaterialPageRoute(
          builder: (_) => FeedItemDetailScreen(items: [items[i]], initialIndex: 0),
        ));
      }
    } catch (_) {
      _notAvailable();
    }
  }

  void _notAvailable() {
    _msgKey.currentState?.showSnackBar(
      const SnackBar(content: Text('Dit item is niet meer beschikbaar.')),
    );
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
    // Geopend via een bookmark terwijl je nog niet ingelogd was? Dan staat
    // de deeplink klaar; open 'm zodra de login lukt.
    ref.listen(authProvider, (prev, next) {
      if (next.isLoggedIn) _maybeOpenDeepLink();
    });
    return MaterialApp(
      title: 'Personal News Feed',
      navigatorKey: _navKey,
      scaffoldMessengerKey: _msgKey,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.red),
        useMaterial3: true,
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

/// Persistente snackbar onderin: toont bij een versie-mismatch, kan
/// weggeswipet worden, en verschijnt opnieuw als er een nieuwere versie komt.
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
        child: Dismissible(
          key: ValueKey(v.backend?.sha ?? 'unknown'),
          direction: DismissDirection.down,
          onDismissed: (_) => ref.read(versionProvider.notifier).dismiss(),
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
      ),
    );
  }
}
