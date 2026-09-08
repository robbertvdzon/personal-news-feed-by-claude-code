import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/auth_provider.dart';
import '../google_signin_button_stub.dart'
    if (dart.library.html) '../google_signin_button_web.dart'
    as gis_button;

class LoginScreen extends ConsumerWidget {
  const LoginScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);
    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 380),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.newspaper, size: 48),
                const SizedBox(height: 16),
                Text(
                  'Inloggen',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 4),
                const Text('Log in met Google om verder te gaan.'),
                const SizedBox(height: 24),
                if (kIsWeb)
                  Center(
                    child: auth.busy
                        ? const Padding(
                            padding: EdgeInsets.symmetric(vertical: 8),
                            child: SizedBox(
                              width: 24,
                              height: 24,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                          )
                        : SizedBox(
                            height: 40,
                            child: gis_button.renderGoogleButton(),
                          ),
                  )
                else
                  FilledButton.icon(
                    onPressed: auth.busy
                        ? null
                        : () =>
                              ref.read(authProvider.notifier).loginWithGoogle(),
                    icon: auth.busy
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.login),
                    label: Text(
                      auth.busy ? 'Inloggen...' : 'Inloggen met Google',
                    ),
                  ),
                if (auth.error != null) ...[
                  const SizedBox(height: 12),
                  Text(
                    auth.error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
