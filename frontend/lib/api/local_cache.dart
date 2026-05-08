import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

/// Eenvoudige offline cache van API-responses.
///
/// Elke list-fetch (feed, rss, podcasts, requests, settings, rss-feeds)
/// wordt na een geslaagde call opgeslagen onder een unieke key in
/// SharedPreferences. Bij netwerkfouten kan een notifier de laatst
/// bekende waarde teruglezen, zodat de Android-app gewoon door blijft
/// werken als internet uit is — de gebruiker leest de feed die hij al
/// eerder had opgehaald.
///
/// De cache is per **gebruiker** gescheiden: de key bevat de username,
/// zodat uitloggen + inloggen als andere user geen vorige cache pakt.
/// Bij uitloggen kan AuthNotifier.logout() de hele cache via [clearAll]
/// wissen; dat staat los van het verwijderen van het JWT-token.
class LocalCache {
  static const _prefix = 'cache_v1_';

  static String _key(String username, String name) => '$_prefix${username}_$name';

  /// Schrijf een lijst-respons (decoded JSON) naar de cache. Stille
  /// no-op als er geen username is — dan kunnen we het toch niet aan
  /// een gebruiker koppelen.
  static Future<void> saveList(String? username, String name, List<dynamic> data) async {
    if (username == null || username.isEmpty) return;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key(username, name), jsonEncode(data));
  }

  /// Schrijf een object-respons (decoded JSON) naar de cache.
  static Future<void> saveObject(
      String? username, String name, Map<String, dynamic> data) async {
    if (username == null || username.isEmpty) return;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key(username, name), jsonEncode(data));
  }

  /// Lees een eerder opgeslagen lijst terug. Geeft null als er nog niets
  /// gecached is voor deze user.
  static Future<List<dynamic>?> loadList(String? username, String name) async {
    if (username == null || username.isEmpty) return null;
    final prefs = await SharedPreferences.getInstance();
    final s = prefs.getString(_key(username, name));
    if (s == null) return null;
    try {
      final v = jsonDecode(s);
      return v is List ? v : null;
    } catch (_) {
      return null;
    }
  }

  /// Lees een eerder opgeslagen object terug.
  static Future<Map<String, dynamic>?> loadObject(String? username, String name) async {
    if (username == null || username.isEmpty) return null;
    final prefs = await SharedPreferences.getInstance();
    final s = prefs.getString(_key(username, name));
    if (s == null) return null;
    try {
      final v = jsonDecode(s);
      return v is Map<String, dynamic> ? v : null;
    } catch (_) {
      return null;
    }
  }

  /// Wis alle cache-entries (alle gebruikers, alle types). Aanroepen bij
  /// uitloggen of als de gebruiker expliciet z'n offline-cache wil
  /// resetten.
  static Future<void> clearAll() async {
    final prefs = await SharedPreferences.getInstance();
    final keys = prefs.getKeys().where((k) => k.startsWith(_prefix)).toList();
    for (final k in keys) {
      await prefs.remove(k);
    }
  }
}
