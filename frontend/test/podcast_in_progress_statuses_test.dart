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

  // kPodcastTranslationInProgressStatuses is de smallere lijst voor de
  // vertaalflow van één RSS-aflevering (EpisodeLookup.translationInProgress).
  test('kPodcastTranslationInProgressStatuses bevat exact de drie '
      'vertaalstatussen', () {
    expect(kPodcastTranslationInProgressStatuses, {
      'PENDING',
      'TRANSLATING',
      'TTS_GENERATING',
    });
  });

  // De eigenlijke vangrail: loopt er ooit een status uit de twee lijsten, dan
  // valt deze test om.
  test('kPodcastTranslationInProgressStatuses is een echte deelverzameling van '
      'kPodcastInProgressStatuses', () {
    expect(
      kPodcastInProgressStatuses.containsAll(
        kPodcastTranslationInProgressStatuses,
      ),
      isTrue,
    );
    expect(
      kPodcastTranslationInProgressStatuses.length <
          kPodcastInProgressStatuses.length,
      isTrue,
    );
  });
}
