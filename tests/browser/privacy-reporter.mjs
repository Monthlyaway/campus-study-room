import { readFileSync, writeFileSync } from 'node:fs';

// Run before all publishing reporters. Locator assertion failures can capture
// their own ARIA snapshot even when PLAYWRIGHT_NO_COPY_PROMPT is set.
export default class PrivacyReporter {
  constructor() {
    this.secrets = [process.env.STUDYROOM_TEST_PASSWORD];
    if (process.env.STUDYROOM_TEST_PASSWORD_FILE)
      this.secrets.push(readFileSync(process.env.STUDYROOM_TEST_PASSWORD_FILE, 'utf8').trim());
    this.secrets = this.secrets.filter(Boolean);
  }

  printsToStdio() { return false; }

  redact(value) {
    for (const secret of this.secrets) value = value.replaceAll(secret, '[REDACTED]');
    return value;
  }

  clean(value, seen = new WeakSet()) {
    if (!value || typeof value !== 'object' || Buffer.isBuffer(value) || seen.has(value)) return;
    seen.add(value);
    for (const [key, child] of Object.entries(value)) {
      if (typeof child === 'string') value[key] = this.redact(child);
      else this.clean(child, seen);
    }
  }

  onStepEnd(_test, _result, step) { this.clean(step); }
  onError(error) { this.clean(error); }

  onTestEnd(_test, result) {
    this.clean(result);
    for (const attachment of result.attachments) {
      if (!/^(text\/|application\/(json|xml))/.test(attachment.contentType)) continue;
      if (attachment.body) attachment.body = Buffer.from(this.redact(attachment.body.toString('utf8')));
      if (attachment.path) {
        const content = readFileSync(attachment.path, 'utf8');
        writeFileSync(attachment.path, this.redact(content));
      }
    }
  }
}
