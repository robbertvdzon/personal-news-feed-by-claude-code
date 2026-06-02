/// Relatieve tijd in het Nederlands sinds [iso] (een ISO-8601 timestamp).
/// Na 3 dagen tonen we een korte datum. Lege/ongeldige input → ''.
String formatRelativeTime(String iso) {
  if (iso.isEmpty) return '';
  final t = DateTime.tryParse(iso);
  if (t == null) return '';
  final diff = DateTime.now().difference(t.toLocal());
  if (diff.inMinutes < 1) return 'zojuist';
  if (diff.inMinutes < 60) return '${diff.inMinutes} min geleden';
  if (diff.inHours < 24) return '${diff.inHours} uur geleden';
  if (diff.inDays <= 3) return '${diff.inDays} dagen geleden';
  final l = t.toLocal();
  return '${l.day.toString().padLeft(2, '0')}-${l.month.toString().padLeft(2, '0')}-${l.year}';
}
