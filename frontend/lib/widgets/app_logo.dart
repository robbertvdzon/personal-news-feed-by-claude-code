import 'package:flutter/material.dart';

/// Klein app-logo voor in de AppBar (leading slot). Zelfde art als de
/// launcher-icon, maar ~32px en met afgeronde rand zodat 'ie matcht
/// met Material's icon-stijl.
class AppLogo extends StatelessWidget {
  final double size;
  const AppLogo({super.key, this.size = 32});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(8),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(size / 5),
        child: Image.asset(
          'assets/app_icon.png',
          width: size,
          height: size,
          fit: BoxFit.cover,
        ),
      ),
    );
  }
}
