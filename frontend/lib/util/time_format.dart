/// Format `when` ten opzichte van nu, in het Nederlands:
///   - < 1 min  → "net binnen"
///   - < 60 min → "X minuten geleden"
///   - < 24 uur → "X uur geleden"
///   - < 3 dagen → "X dag(en) geleden"
///   - daarna → "DD-MM-YYYY"
///
/// Geeft een lege string terug bij een leeg/ongeldig invoer-string zodat
/// de UI niet crasht op legacy items zonder timestamp.
String formatRelativeTime(String iso) {
  if (iso.isEmpty) return '';
  final when = DateTime.tryParse(iso);
  if (when == null) return '';
  final now = DateTime.now();
  final diff = now.difference(when.toLocal());

  if (diff.isNegative) return 'zojuist';
  if (diff.inMinutes < 1) return 'net binnen';
  if (diff.inMinutes < 60) {
    final m = diff.inMinutes;
    return m == 1 ? '1 minuut geleden' : '$m minuten geleden';
  }
  if (diff.inHours < 24) {
    final h = diff.inHours;
    return h == 1 ? '1 uur geleden' : '$h uur geleden';
  }
  if (diff.inDays < 3) {
    final d = diff.inDays;
    return d == 1 ? '1 dag geleden' : '$d dagen geleden';
  }
  // Na 3 dagen → absolute datum (lokale tijd)
  final local = when.toLocal();
  final dd = local.day.toString().padLeft(2, '0');
  final mm = local.month.toString().padLeft(2, '0');
  return '$dd-$mm-${local.year}';
}
