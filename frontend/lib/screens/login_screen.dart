import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/auth_provider.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _user = TextEditingController();
  final _pass = TextEditingController();
  bool _showPassword = false;
  bool _registerMode = false;
  bool _busy = false;

  Future<void> _submit() async {
    setState(() => _busy = true);
    if (_registerMode) {
      await ref.read(authProvider.notifier).register(_user.text.trim(), _pass.text);
    } else {
      await ref.read(authProvider.notifier).login(_user.text.trim(), _pass.text);
    }
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
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
                  _registerMode ? 'Account aanmaken' : 'Inloggen',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 24),
                TextField(
                  controller: _user,
                  decoration: const InputDecoration(labelText: 'Gebruikersnaam'),
                  textInputAction: TextInputAction.next,
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _pass,
                  obscureText: !_showPassword,
                  decoration: InputDecoration(
                    labelText: 'Wachtwoord',
                    suffixIcon: IconButton(
                      icon: Icon(_showPassword ? Icons.visibility_off : Icons.visibility),
                      onPressed: () => setState(() => _showPassword = !_showPassword),
                    ),
                  ),
                  onSubmitted: (_) => _submit(),
                ),
                if (auth.error != null) ...[
                  const SizedBox(height: 12),
                  Text(auth.error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: _busy ? null : _submit,
                  child: _busy
                      ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                      : Text(_registerMode ? 'Account aanmaken' : 'Inloggen'),
                ),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: () => setState(() => _registerMode = !_registerMode),
                  child: Text(_registerMode ? 'Heb je al een account? Inloggen' : 'Account aanmaken'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
