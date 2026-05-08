import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';

class ItemCard extends StatelessWidget {
  final String title;
  final String source;
  final String category;
  /// Originele publicatiedatum (`YYYY-MM-DD`), uit de RSS-feed.
  final String? date;
  /// Relatieve tijd sinds het item bij ons binnenkwam ("12 minuten geleden",
  /// "3 uur geleden", "2 dagen geleden", of een datum na 3 dagen).
  /// Wordt naast de bron-naam getoond. Leeg = niet tonen.
  final String relativeTime;
  final String snippet;
  final bool isRead;
  final bool starred;
  final bool? liked;
  final Widget? trailing;
  final VoidCallback? onTap;
  final VoidCallback? onStar;
  final ValueChanged<bool?>? onFeedback;
  final VoidCallback? onDelete;

  const ItemCard({
    super.key,
    required this.title,
    required this.source,
    required this.category,
    required this.snippet,
    this.date,
    this.relativeTime = '',
    this.isRead = false,
    this.starred = false,
    this.liked,
    this.trailing,
    this.onTap,
    this.onStar,
    this.onFeedback,
    this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return Dismissible(
      key: Key('item_${title.hashCode}_$source'),
      direction: onDelete == null ? DismissDirection.none : DismissDirection.endToStart,
      onDismissed: (_) => onDelete?.call(),
      background: Container(
        color: Colors.red,
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 16),
        child: const Icon(Icons.delete, color: Colors.white),
      ),
      child: Card(
        child: ListTile(
          onTap: onTap,
          title: Text(
            title,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontWeight: isRead ? FontWeight.normal : FontWeight.bold,
              color: isRead ? Theme.of(context).hintColor : null,
            ),
          ),
          subtitle: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 4),
              Wrap(spacing: 8, runSpacing: 2, crossAxisAlignment: WrapCrossAlignment.center, children: [
                if (source.isNotEmpty) Text(source, style: Theme.of(context).textTheme.bodySmall),
                if (relativeTime.isNotEmpty)
                  Text(
                    '· $relativeTime',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).hintColor,
                    ),
                  ),
                Chip(
                  label: Text(category),
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                if (date != null) Text(date!, style: Theme.of(context).textTheme.bodySmall),
                if (trailing != null) trailing!,
              ]),
              const SizedBox(height: 4),
              // Render preview als markdown — een fallback op de lange
              // `summary` (bij legacy items zonder `shortSummary`) bevat
              // vaak **vet**, *cursief* of `code`. Beperken tot ongeveer
              // 2 regels door de tekst eerst af te kappen op ~250 chars
              // / eerste paragraaf, want MarkdownBody heeft geen maxLines.
              MarkdownBody(
                data: _truncateForPreview(snippet),
                shrinkWrap: true,
                styleSheet: MarkdownStyleSheet.fromTheme(Theme.of(context)).copyWith(
                  p: Theme.of(context).textTheme.bodyMedium,
                  // Headers + lijsten zijn in een 2-regel preview niet
                  // bruikbaar; render ze als gewone tekst.
                  h1: Theme.of(context).textTheme.bodyMedium,
                  h2: Theme.of(context).textTheme.bodyMedium,
                  h3: Theme.of(context).textTheme.bodyMedium,
                ),
              ),
              Row(children: [
                IconButton(
                  icon: Icon(liked == true ? Icons.thumb_up : Icons.thumb_up_outlined,
                      color: liked == true ? Colors.green : null),
                  onPressed: onFeedback == null ? null : () => onFeedback!(liked == true ? null : true),
                ),
                IconButton(
                  icon: Icon(liked == false ? Icons.thumb_down : Icons.thumb_down_outlined,
                      color: liked == false ? Colors.red : null),
                  onPressed: onFeedback == null ? null : () => onFeedback!(liked == false ? null : false),
                ),
                IconButton(
                  icon: Icon(starred ? Icons.star : Icons.star_outline,
                      color: starred ? Colors.amber : null),
                  onPressed: onStar,
                ),
              ]),
            ],
          ),
        ),
      ),
    );
  }
}

/// Trim de preview-tekst tot ongeveer 2 regels markdown. We pakken eerst
/// de eerste paragraaf (split op blank line) en kappen daarna af op een
/// grenswoord rond 240 chars, met een ellipsis. Voorkomt dat een lange
/// markdown-summary de hele kaart laat groeien.
String _truncateForPreview(String src) {
  final firstPara = src.split(RegExp(r'\n\s*\n')).first.trim();
  const maxLen = 240;
  if (firstPara.length <= maxLen) return firstPara;
  final cut = firstPara.lastIndexOf(' ', maxLen);
  final at = cut > maxLen - 60 ? cut : maxLen;
  return '${firstPara.substring(0, at).trimRight()}…';
}
