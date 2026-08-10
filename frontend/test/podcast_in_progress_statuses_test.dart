import 'package:flutter_test/flutter_test.dart';
import 'package:personal_news_feed/models/models.dart';

void main() {
  // kPodcastInProgressStatuses is de enige bron voor "podcast is nog bezig":
  // zowel de spinner/het statuslabel in het podcast-overzicht als de poll-timer
  // (overzicht en detailscherm) lezen deze set. Deze test legt de zes statussen
  // vast, zodat spinner en poll-timer niet opnieuw uit elkaar kunnen lopen.
  test('kPodcastInProgressStatuses bevat exact de zes bezig-statussen', () {
    expect(kPodcastInProgressStatuses, {
      'PENDING',
      'DETERMINING_TOPICS',
      'GENERATING_SCRIPT',
      'GENERATING_AUDIO',
      'TRANSLATING',
      'TTS_GENERATING',
    });
  });

  test('afgeronde statussen tellen niet als bezig', () {
    expect(kPodcastInProgressStatuses.contains('DONE'), isFalse);
    expect(kPodcastInProgressStatuses.contains('FAILED'), isFalse);
  });
}
