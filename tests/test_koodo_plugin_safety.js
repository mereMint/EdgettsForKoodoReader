const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { Readable } = require('node:stream');
const vm = require('node:vm');

function loadPlugin({ failOnAxios = false, axios = null } = {}) {
  const script = fs.readFileSync(path.join(__dirname, '..', 'koodo_plugin', 'edgeTTS.js'), 'utf8');
  const context = {
    Buffer,
    console,
    global: {},
    require: (name) => {
      if (name === 'fs') return fs;
      if (name === 'path') return path;
      if (name === 'stream/promises') return require('node:stream/promises');
      if (name === 'axios') {
        if (failOnAxios) throw new Error('axios should not be required for blank/image-only pages');
        return axios || { post: async () => { throw new Error('not stubbed'); } };
      }
      throw new Error(`unexpected require: ${name}`);
    },
  };
  vm.createContext(context);
  vm.runInContext(script, context, { filename: 'edgeTTS.js' });
  return context.global.getAudioPath;
}

test('blank and image-only pages reuse one silent wav and do not require axios', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-tts-plugin-'));
  try {
    const getAudioPath = loadPlugin({ failOnAxios: true });
    const inputs = [
      '',
      '<section><img src="cover.jpg" alt="cover"></section>',
      '![cover image](cover.png)',
      '&nbsp; <br/> \n',
    ];

    const paths = [];
    for (const input of inputs) {
      paths.push(await getAudioPath(input, 1, dir, {}));
    }

    assert.equal(new Set(paths).size, 1, 'all blank/image-only requests should reuse one silent file');
    const files = fs.readdirSync(path.join(dir, 'tts')).filter((file) => file.endsWith('.wav'));
    assert.deepEqual(files, ['silent.wav']);
    const silent = fs.readFileSync(paths[0]);
    assert.equal(silent.subarray(0, 4).toString('ascii'), 'RIFF');
    assert.equal(silent.subarray(8, 12).toString('ascii'), 'WAVE');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('normal TTS responses are streamed to disk instead of buffered as arraybuffer', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-tts-plugin-'));
  const wav = Buffer.concat([
    Buffer.from('RIFF$\x00\x00\x00WAVEfmt ', 'binary'),
    Buffer.alloc(128, 1),
  ]);
  let requestOptions;
  try {
    const axios = {
      post: async (_url, _payload, options) => {
        requestOptions = options;
        return { data: Readable.from([wav]) };
      },
    };
    const getAudioPath = loadPlugin({ axios });
    const audioPath = await getAudioPath('This is real text.', 1, dir, {});

    assert.equal(requestOptions.responseType, 'stream');
    assert.equal(fs.readFileSync(audioPath).compare(wav), 0);
    assert.equal(path.basename(audioPath).endsWith('.wav'), true);
    assert.equal(fs.existsSync(audioPath + '.part'), false, 'partial file should be renamed away');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
