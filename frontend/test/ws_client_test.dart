import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/api/api_client.dart';
import 'package:personal_news_feed/api/ws_client.dart';

/// SF-2166: `/ws/requests` authenticeert op de handshake, dus de app moet
/// het JWT als queryparameter meesturen — bij connect én bij elke reconnect
/// (beide gaan door [requestsWsUrl]) — en zonder token niet verbinden.
void main() {
  String wsBase() => ApiClient.baseUrl.replaceFirst(RegExp(r'^http'), 'ws');

  test('de ws-url krijgt het token als queryparameter', () {
    final url = requestsWsUrl('jwt-abc');

    expect(url, '${wsBase()}/ws/requests?token=jwt-abc');
  });

  test('de ws-url is http->ws omgezet en wijst naar /ws/requests', () {
    final url = requestsWsUrl('jwt-abc')!;

    expect(url.startsWith('ws'), isTrue, reason: 'schema hoort ws(s) te zijn: $url');
    expect(Uri.parse(url).path, '/ws/requests');
    expect(Uri.parse(url).queryParameters['token'], 'jwt-abc');
  });

  test('een token met url-onvriendelijke tekens wordt gecodeerd', () {
    final url = requestsWsUrl('a b+c/d=')!;

    expect(url.contains(' '), isFalse);
    expect(Uri.parse(url).queryParameters['token'], 'a b+c/d=');
  });

  test('zonder token wordt er geen url gebouwd (dus niet verbonden)', () {
    expect(requestsWsUrl(null), isNull);
    expect(requestsWsUrl(''), isNull);
  });

  test('connect zonder token opent geen verbinding en levert een lege stream', () async {
    final ws = RequestsWebSocket();

    final stream = ws.connect(null);
    final messages = <Map<String, dynamic>>[];
    final sub = stream.listen(messages.add);
    await Future<void>.delayed(const Duration(milliseconds: 50));

    expect(messages, isEmpty);
    await sub.cancel();
    ws.close();
  });
}
