import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Houdt per item bij of het gelezen is en/of bewaard met een sterretje.
///
/// Volledig lokaal op het toestel (SharedPreferences) — er is geen
/// account en de server weet hier niks van. Status synct dus niet tussen
/// toestellen en gaat verloren als app-data gewist wordt.
class ReadStore extends ChangeNotifier {
  static const _readKey = 'read_ids';
  static const _starKey = 'starred_ids';

  final Set<String> _read = {};
  final Set<String> _starred = {};
  SharedPreferences? _prefs;

  Future<void> load() async {
    _prefs = await SharedPreferences.getInstance();
    _read
      ..clear()
      ..addAll(_prefs!.getStringList(_readKey) ?? const []);
    _starred
      ..clear()
      ..addAll(_prefs!.getStringList(_starKey) ?? const []);
    notifyListeners();
  }

  bool isRead(String id) => _read.contains(id);
  bool isStarred(String id) => _starred.contains(id);

  void markRead(String id) {
    if (_read.add(id)) {
      _prefs?.setStringList(_readKey, _read.toList());
      notifyListeners();
    }
  }

  void setRead(String id, bool read) {
    final changed = read ? _read.add(id) : _read.remove(id);
    if (changed) {
      _prefs?.setStringList(_readKey, _read.toList());
      notifyListeners();
    }
  }

  void toggleStar(String id) {
    if (_starred.contains(id)) {
      _starred.remove(id);
    } else {
      _starred.add(id);
    }
    _prefs?.setStringList(_starKey, _starred.toList());
    notifyListeners();
  }
}
