import 'package:flutter/material.dart';

class ItemCard extends StatelessWidget {
  final String title;
  final String source;
  final String category;
  final String? date;
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
              Wrap(spacing: 8, children: [
                if (source.isNotEmpty) Text(source, style: Theme.of(context).textTheme.bodySmall),
                Chip(
                  label: Text(category),
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                if (date != null) Text(date!, style: Theme.of(context).textTheme.bodySmall),
                if (trailing != null) trailing!,
              ]),
              const SizedBox(height: 4),
              Text(snippet, maxLines: 2, overflow: TextOverflow.ellipsis),
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
