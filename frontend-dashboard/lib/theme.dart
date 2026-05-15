import 'package:flutter/material.dart';

/// Modern Material 3 theme — soft, card-based, met indigo als seed.
/// Light + dark, default volgt het systeem.
class AppTheme {
  static const Color _seed = Color(0xFF6366F1); // indigo-500

  static ThemeData light() {
    final scheme = ColorScheme.fromSeed(seedColor: _seed);
    return _base(scheme);
  }

  static ThemeData dark() {
    final scheme = ColorScheme.fromSeed(
      seedColor: _seed,
      brightness: Brightness.dark,
    );
    return _base(scheme);
  }

  static ThemeData _base(ColorScheme scheme) {
    final isLight = scheme.brightness == Brightness.light;
    return ThemeData(
      colorScheme: scheme,
      useMaterial3: true,
      visualDensity: VisualDensity.adaptivePlatformDensity,
      // Witte achtergrond in light-mode (zoals PNF-feed-app),
      // surface-color in dark-mode.
      scaffoldBackgroundColor: isLight ? Colors.white : scheme.surface,
      appBarTheme: AppBarTheme(
        backgroundColor: isLight ? Colors.white : scheme.surface,
        foregroundColor: scheme.onSurface,
        elevation: 0,
        scrolledUnderElevation: 1,
        centerTitle: false,
        titleTextStyle: TextStyle(
          color: scheme.onSurface,
          fontSize: 20,
          fontWeight: FontWeight.w600,
        ),
      ),
      cardTheme: CardThemeData(
        // In light-mode geven cards een subtiele tint t.o.v. de witte
        // achtergrond zodat ze 'zweven'. In dark-mode container-color.
        color: isLight ? const Color(0xFFF6F7FB) : scheme.surfaceContainerLow,
        elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        margin: EdgeInsets.zero,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: isLight ? const Color(0xFFF1F3F8) : scheme.surfaceContainerHighest,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide.none,
        ),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      ),
      chipTheme: ChipThemeData(
        side: BorderSide.none,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
      dividerTheme: DividerThemeData(
        color: scheme.outlineVariant,
        thickness: 1,
        space: 1,
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: scheme.surface,
        indicatorColor: scheme.secondaryContainer,
        elevation: 1,
      ),
    );
  }
}
