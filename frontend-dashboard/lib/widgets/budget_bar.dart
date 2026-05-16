import 'package:flutter/material.dart';

/// Visualiseert tokens-used vs. AI Token Budget (KAN-42). Kleur volgt
/// dezelfde drempels als de poller's [COST-MONITOR] markers:
///   * groen  < 75%
///   * geel   75–99%
///   * rood   >= 100%
/// Bij `budget == 0` toont 'n.v.t.' (geen budget gezet voor deze story).
class BudgetBar extends StatelessWidget {
  final int tokensUsed;
  final int tokenBudget;
  final double height;
  final bool showLabel;
  const BudgetBar({
    super.key,
    required this.tokensUsed,
    required this.tokenBudget,
    this.height = 6,
    this.showLabel = false,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    if (tokenBudget <= 0) {
      return Text('n.v.t.',
          style: TextStyle(
              fontSize: 11,
              color: scheme.onSurfaceVariant,
              fontStyle: FontStyle.italic));
    }
    final pct = (tokensUsed / tokenBudget).clamp(0.0, 1.5);
    final Color color;
    if (pct >= 1.0) {
      color = const Color(0xFFC62828); // rood
    } else if (pct >= 0.75) {
      color = const Color(0xFFE6A100); // geel/oranje
    } else {
      color = const Color(0xFF1E6B3E); // groen
    }
    // Cap visible bar at 100% — over-budget krijgt rood + bar gevuld.
    final visible = pct.clamp(0.0, 1.0);
    final pctInt = (pct * 100).round();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (showLabel)
          Padding(
            padding: const EdgeInsets.only(bottom: 4),
            child: Text(
              '$pctInt% · ${_fmt(tokensUsed)} / ${_fmt(tokenBudget)} tokens',
              style: TextStyle(
                  fontSize: 11,
                  color: scheme.onSurfaceVariant,
                  fontWeight: FontWeight.w600),
            ),
          ),
        ClipRRect(
          borderRadius: BorderRadius.circular(height),
          child: Stack(
            children: [
              Container(
                height: height,
                color: scheme.surfaceContainerHighest,
              ),
              FractionallySizedBox(
                alignment: Alignment.centerLeft,
                widthFactor: visible,
                child: Container(height: height, color: color),
              ),
            ],
          ),
        ),
      ],
    );
  }

  static String _fmt(int n) {
    if (n >= 1000000) return '${(n / 1000000).toStringAsFixed(1)}M';
    if (n >= 1000) return '${(n / 1000).toStringAsFixed(1)}K';
    return '$n';
  }
}
