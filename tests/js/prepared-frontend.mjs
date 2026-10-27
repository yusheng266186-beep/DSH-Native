// Fixtures consume the published Android document, including its early probes.
// Only the device viewport changes; adapters must never be injected a second time.
import assert from 'node:assert/strict';
export function preparedFrontend(html, reference, width=480) {
  for (const [tag,id] of [['style','dsh-native-responsive'],['script','dsh-native-session-probe'],
    ['script','dsh-native-connection-watch'],['script','dsh-native-draft-recovery']]) {
    const expression=new RegExp('<'+tag+' id="'+id+'">[\\s\\S]*?</'+tag+'>','g');
    const actual=html.match(expression)||[],expected=reference.match(expression)||[];
    assert.equal(actual.length,1,'published Android document has one '+id);
    assert.equal(expected.length,1,'reference adapter has one '+id);
    assert.equal(actual[0],expected[0],'published adapter matches production '+id);
  }
  const viewport=/<meta\b[^>]*name=["']viewport["'][^>]*>/ig;
  assert.equal((html.match(viewport)||[]).length,1,'published document has one viewport');
  return html.replace(viewport,'<meta name="viewport" content="width='+Math.max(480,Math.min(width,1440))+'" />');
}
