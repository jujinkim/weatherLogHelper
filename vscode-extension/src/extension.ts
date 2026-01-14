import * as vscode from 'vscode';
import { execFile } from 'child_process';
import * as os from 'os';
import * as path from 'path';
import * as fs from 'fs';

const output = vscode.window.createOutputChannel('WLH');

type CrashEntry = {
  line: number;
  preview: string;
};

type VersionEntry = {
  line: number;
  label: string;
};

type ScanResults = {
  filePath: string;
  versions: Array<VersionEntry | string>;
  crashes: CrashEntry[];
};

type PackageGroup = {
  name: string;
  crashes: CrashEntry[];
};

type EngineConfig = {
  scanPackages: string[];
};

function normalizeResults(raw: Partial<ScanResults> & { file?: string }): ScanResults {
  const normalizeVersions = (value: unknown): Array<VersionEntry | string> => {
    if (!Array.isArray(value)) {
      return [];
    }
    return value
      .map((entry) => {
        if (typeof entry === 'string') {
          return entry;
        }
        if (entry && typeof entry === 'object') {
          const maybe = entry as { line?: unknown; label?: unknown };
          const line = typeof maybe.line === 'number' ? maybe.line : 0;
          const label = typeof maybe.label === 'string' ? maybe.label : '';
          return { line, label };
        }
        return '';
      })
      .filter((entry) => (typeof entry === 'string' ? entry.length > 0 : entry.label.length > 0));
  };
  return {
    filePath: raw.filePath || raw.file || 'Unknown file',
    versions: normalizeVersions(raw.versions),
    crashes: Array.isArray(raw.crashes) ? raw.crashes : []
  };
}

class WlhSidebarProvider implements vscode.WebviewViewProvider {
  private view: vscode.WebviewView | undefined;
  private lastContent = 'No scans yet.';
  private lastStatus = 'Idle';
  private lastFilePath = 'No file selected';
  private currentResults: ScanResults | undefined;
  private packageGroups: PackageGroup[] = [];
  private scanPackages: string[] = [];

  constructor(
    private readonly onJump: (filePath: string, line: number) => void,
    private readonly onDecrypt: (filePath: string) => void,
    private readonly onOpenSettings: () => void,
    private readonly onStatus: () => void,
    private readonly onStart: () => void,
    private readonly onRestart: () => void,
    private readonly onStop: () => void,
    private readonly onRunEngineDirect: () => void,
    private readonly onOpenHome: () => void,
    private readonly onScan: (filePath: string) => void
  ) {}

  resolveWebviewView(webviewView: vscode.WebviewView): void {
    this.view = webviewView;
    webviewView.webview.options = {
      enableScripts: true
    };
    webviewView.webview.onDidReceiveMessage((message) => {
      if (message?.type === 'jump' && this.currentResults) {
        const line = Number(message.line);
        if (!Number.isNaN(line)) {
          this.onJump(this.currentResults.filePath, line);
        }
      }
      if (message?.type === 'decrypt') {
        const filePath = this.currentResults?.filePath || resolveActiveFilePath();
        if (filePath) {
          this.onDecrypt(filePath);
        } else {
          vscode.window.showErrorMessage('WLH: No active file to decrypt.');
        }
      }
      if (message?.type === 'openSettings') {
        this.onOpenSettings();
      }
      if (message?.type === 'status') {
        this.onStatus();
      }
      if (message?.type === 'start') {
        this.onStart();
      }
      if (message?.type === 'restart') {
        this.onRestart();
      }
      if (message?.type === 'stop') {
        this.onStop();
      }
      if (message?.type === 'runEngineDirect') {
        this.onRunEngineDirect();
      }
      if (message?.type === 'openHome') {
        this.onOpenHome();
      }
      if (message?.type === 'openEngineConfig') {
        openEngineConfig();
      }
      if (message?.type === 'scan') {
        const filePath = this.currentResults?.filePath || resolveActiveFilePath();
        if (filePath) {
          this.onScan(filePath);
        } else {
          vscode.window.showErrorMessage('WLH: No active file to scan.');
        }
      }
    });
    this.render();
  }

  update(content: string) {
    this.lastContent = content;
    this.render();
  }

  updateStatus(content: string) {
    this.lastStatus = content;
    this.render();
  }

  updateFilePath(filePath: string) {
    this.lastFilePath = filePath;
    this.render();
  }

  updateResults(results: ScanResults, groups: PackageGroup[] = [], scanPackages: string[] = []) {
    this.currentResults = results;
    this.lastFilePath = results.filePath;
    this.packageGroups = groups;
    this.scanPackages = scanPackages;
    this.lastContent = '';
    this.render();
  }

  private render() {
    if (!this.view) {
      return;
    }
    if (this.currentResults) {
      this.view.webview.html = this.renderResults(
        this.currentResults,
        undefined,
        this.packageGroups,
        this.scanPackages
      );
      return;
    }

    const emptyResults: ScanResults = {
      filePath: this.lastFilePath,
      versions: [],
      crashes: []
    };
    this.view.webview.html = this.renderResults(
      emptyResults,
      this.lastContent,
      this.packageGroups,
      this.scanPackages
    );
  }

  private renderResults(
    results: ScanResults,
    message?: string,
    packageGroups: PackageGroup[] = [],
    scanPackages: string[] = []
  ): string {
    const escape = (value: string | undefined | null) =>
      (value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const versions = results.versions.length
      ? results.versions
          .map((v) => {
            if (typeof v === 'string') {
              return `<li>${escape(v)}</li>`;
            }
            const entry = v as VersionEntry;
            const label = entry.label ?? '';
            return entry.line > 0
              ? `<li><button class="jump" data-line="${entry.line}">L${entry.line}</button> ${escape(label)}</li>`
              : `<li>${escape(label)}</li>`;
          })
          .join('')
      : '<li>None</li>';
    const crashes = results.crashes.length
      ? results.crashes
          .map(
            (c) =>
              `<li><button class="jump" data-line="${c.line}">L${c.line}</button> ${escape(
                c.preview
              )}</li>`
          )
          .join('')
      : '<li>None</li>';
    const renderEntries = (entries: CrashEntry[]) =>
      entries.length
        ? entries
            .map((entry) => {
              return `<li><button class="jump" data-line="${entry.line}">L${entry.line}</button> ${escape(
                entry.preview
              )}</li>`;
            })
            .join('')
        : '<li>None</li>';

    const packageOptions: string[] = [];
    const packageSections =
      packageGroups.length > 0
        ? packageGroups
            .map((group, index) => {
              const value = `pkg-${index}`;
              packageOptions.push(`<option value="${value}">${escape(group.name)}</option>`);
              return `
                <div class="section" data-package="${value}">
                  <h4>Package: ${escape(group.name)}</h4>
                  <div class="subsection">
                    <h4>Crashes</h4>
                    <ul>${renderEntries(group.crashes)}</ul>
                  </div>
                </div>
              `;
            })
            .join('')
        : '';

    const messageSection = message
      ? `<h4>Message</h4><div class="message">${escape(message)}</div>`
      : '';

    return `<!DOCTYPE html>
      <html>
        <head>
          <style>
            * { box-sizing: border-box; }
            body {
              margin: 0;
              padding: 16px;
              font-family: var(--vscode-font-family, "Segoe UI", "Noto Sans", sans-serif);
              color: var(--vscode-foreground);
              background: var(--vscode-sideBar-background);
            }
            h3 {
              margin: 0 0 6px 0;
              font-size: 16px;
              color: var(--vscode-foreground);
            }
            .card {
              background: var(--vscode-sideBar-background);
              border: 1px solid var(--vscode-panel-border);
              border-radius: 10px;
              padding: 16px;
            }
            .path {
              font-size: 12px;
              color: var(--vscode-descriptionForeground);
              margin-bottom: 10px;
              word-break: break-all;
            }
            .actions {
              display: flex;
              flex-wrap: wrap;
              gap: 8px;
              margin: 10px 0 12px 0;
            }
            .section {
              margin-top: 14px;
            }
            .subsection {
              margin-top: 8px;
            }
            button {
              background: var(--vscode-button-background);
              border: 1px solid var(--vscode-button-border, transparent);
              color: var(--vscode-button-foreground);
              padding: 6px 10px;
              border-radius: 6px;
              font-size: 11px;
              cursor: pointer;
            }
            button:hover {
              background: var(--vscode-button-hoverBackground);
            }
            .status {
              margin: 8px 0 16px 0;
              padding: 8px 10px;
              border-radius: 6px;
              border: 1px solid var(--vscode-panel-border);
              background: var(--vscode-editorWidget-background);
              font-size: 12px;
            }
            h4 {
              margin: 14px 0 6px 0;
              font-size: 13px;
              text-transform: uppercase;
              letter-spacing: 0.06em;
              color: var(--vscode-descriptionForeground);
            }
            ul {
              margin: 0;
              padding-left: 16px;
            }
            li {
              margin-bottom: 6px;
              font-size: 12px;
              white-space: pre-wrap;
            }
            .jump {
              background: transparent;
              border: 1px solid var(--vscode-editorLink-activeForeground);
              color: var(--vscode-editorLink-activeForeground);
              padding: 2px 6px;
              border-radius: 6px;
              margin-right: 6px;
              font-size: 11px;
            }
            .message {
              font-size: 12px;
              color: var(--vscode-descriptionForeground);
              white-space: pre-wrap;
            }
            select {
              background: var(--vscode-dropdown-background);
              color: var(--vscode-dropdown-foreground);
              border: 1px solid var(--vscode-dropdown-border);
              border-radius: 6px;
              padding: 6px 8px;
              font-size: 12px;
              width: 100%;
            }
          </style>
        </head>
        <body>
          <div class="card">
            <h3>Weather Log Helper</h3>
            <div class="actions">
              <button id="status">Status</button>
              <button id="start">Start</button>
              <button id="restart">Restart</button>
              <button id="stop">Stop</button>
              <button id="runEngineDirect">Run Engine Direct</button>
              <button id="openHome">Open WLH Home</button>
              <button id="openSettings">Open Settings</button>
              <button id="openEngineConfig">Open Engine Config</button>
            </div>
            <div class="section">
              <h4>Scan</h4>
              <div class="path">${escape(results.filePath)}</div>
              <div class="actions">
                <button id="scan">Run Full Scan</button>
                <button id="decrypt">Run Decrypt</button>
              </div>
            </div>
            <div class="status">Status: ${escape(this.lastStatus)}</div>
            ${messageSection}
            <div class="section">
              <h4>Package Filter</h4>
              ${scanPackages.length === 0 ? '<div class="message">No packages configured.</div>' : `
                <select id="packageSelect">
                  ${packageOptions.join('')}
                </select>
              `}
            </div>
            <h4>Versions</h4>
            <ul>${versions}</ul>
            <h4>Crashes</h4>
            <ul>${crashes}</ul>
            ${packageSections}
          </div>
          <script>
            const vscode = acquireVsCodeApi();
            document.getElementById('status').addEventListener('click', () => {
              vscode.postMessage({ type: 'status' });
            });
            document.getElementById('start').addEventListener('click', () => {
              vscode.postMessage({ type: 'start' });
            });
            document.getElementById('restart').addEventListener('click', () => {
              vscode.postMessage({ type: 'restart' });
            });
            document.getElementById('stop').addEventListener('click', () => {
              vscode.postMessage({ type: 'stop' });
            });
            document.getElementById('runEngineDirect').addEventListener('click', () => {
              vscode.postMessage({ type: 'runEngineDirect' });
            });
            document.getElementById('openHome').addEventListener('click', () => {
              vscode.postMessage({ type: 'openHome' });
            });
            document.getElementById('openEngineConfig').addEventListener('click', () => {
              vscode.postMessage({ type: 'openEngineConfig' });
            });
            document.getElementById('scan').addEventListener('click', () => {
              vscode.postMessage({ type: 'scan' });
            });
            document.getElementById('decrypt').addEventListener('click', () => {
              vscode.postMessage({ type: 'decrypt' });
            });
            document.getElementById('openSettings').addEventListener('click', () => {
              vscode.postMessage({ type: 'openSettings' });
            });
            const packageSelect = document.getElementById('packageSelect');
            if (packageSelect) {
              const applyFilter = () => {
                const value = packageSelect.value;
                document.querySelectorAll('[data-package]').forEach((section) => {
                  section.style.display = section.getAttribute('data-package') === value ? '' : 'none';
                });
              };
              applyFilter();
              packageSelect.addEventListener('change', applyFilter);
            }
            document.querySelectorAll('button[data-line]').forEach((button) => {
              button.addEventListener('click', () => {
                vscode.postMessage({ type: 'jump', line: button.dataset.line });
              });
            });
          </script>
        </body>
      </html>`;
  }
}

function normalizeCommandPath(value: string): string {
  const trimmed = value.trim();
  if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
    return trimmed.slice(1, -1);
  }
  if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function isWindows(): boolean {
  return process.platform === 'win32';
}

function resolveWlhCommand(commandPath: string, args: string[]): { command: string; args: string[] } {
  const normalized = normalizeCommandPath(commandPath);
  const lower = normalized.toLowerCase();
  if (lower.endsWith('.sh')) {
    const shell = isWindows() ? 'bash.exe' : 'bash';
    return { command: shell, args: [normalized, ...args] };
  }
  if (lower.endsWith('.bat') || lower.endsWith('.cmd')) {
    return { command: 'cmd', args: ['/c', normalized, ...args] };
  }
  return { command: normalized, args };
}

function runWlh(args: string[]): Promise<string> {
  const config = vscode.workspace.getConfiguration('wlh');
  const baseUrl = config.get<string>('update.baseUrl');
  const commandPath = config.get<string>('commandPath') || 'wlh';
  const normalizedPath = normalizeCommandPath(commandPath);
  if (!commandPath || normalizedPath.trim().length === 0) {
    vscode.window.showErrorMessage('WLH: Configure wlh.commandPath to the bootstrap script.');
    return Promise.reject(new Error('wlh_command_missing'));
  }
  if (isWindows() && normalizedPath.toLowerCase().endsWith('.sh')) {
    vscode.window.showErrorMessage('WLH: Use the .bat bootstrap on Windows.');
    return Promise.reject(new Error('wlh_command_windows_sh'));
  }
  if ((normalizedPath.includes('/') || normalizedPath.includes('\\')) && !fs.existsSync(normalizedPath)) {
    vscode.window.showErrorMessage(`WLH: command path not found: ${normalizedPath}`);
    return Promise.reject(new Error('wlh_command_not_found'));
  }
  const finalArgs = [...args];
  if (baseUrl && baseUrl.length > 0) {
    finalArgs.unshift(baseUrl);
    finalArgs.unshift('--base-url');
  }
  const resolved = resolveWlhCommand(commandPath, finalArgs);

  return new Promise((resolve, reject) => {
    output.appendLine(`WLH exec: ${resolved.command} ${resolved.args.join(' ')}`);
    execFile(
      resolved.command,
      resolved.args,
      { maxBuffer: 10 * 1024 * 1024 },
      (err, stdout, stderr) => {
      if (stderr) {
        output.appendLine(stderr.trim());
      }
      if (err) {
        output.appendLine(`WLH exec error: ${err.message}`);
        const errInfo = err as NodeJS.ErrnoException & { signal?: string };
        const code = errInfo.code;
        const signal = errInfo.signal;
        output.appendLine(`WLH exec error details: code=${code ?? 'unknown'} signal=${signal ?? 'none'}`);
        if (stdout && stdout.trim().length > 0) {
          resolve(stdout.trim());
          return;
        }
        reject(err);
        return;
      }
      resolve(stdout.trim());
    }
    );
  });
}

async function runWlhJson<T>(args: string[]): Promise<T> {
  const raw = await runWlh(args);
  return JSON.parse(raw) as T;
}

function resolveEngineConfigPath(): string {
  return path.join(resolveDefaultHome(), 'config', 'wlh.json');
}

function ensureHomeDirectory() {
  fs.mkdirSync(resolveDefaultHome(), { recursive: true });
  fs.mkdirSync(path.join(resolveDefaultHome(), 'config'), { recursive: true });
}

function readEngineConfig(): EngineConfig {
  try {
    const configPath = resolveEngineConfigPath();
    if (!fs.existsSync(configPath)) {
      return { scanPackages: [] };
    }
    const raw = fs.readFileSync(configPath, 'utf8');
    const parsed = JSON.parse(raw) as Partial<EngineConfig>;
    return {
      scanPackages: Array.isArray(parsed.scanPackages) ? parsed.scanPackages : []
    };
  } catch {
    return { scanPackages: [] };
  }
}

function openEngineConfig() {
  try {
    ensureHomeDirectory();
    const configPath = resolveEngineConfigPath();
    if (!fs.existsSync(configPath)) {
      const template = {
        scanPackages: []
      };
      fs.writeFileSync(configPath, JSON.stringify(template, null, 2));
    }
    vscode.workspace.openTextDocument(configPath).then((doc) => {
      vscode.window.showTextDocument(doc, { preview: false });
    });
  } catch (err) {
    vscode.window.showErrorMessage('WLH: Failed to open engine config.');
    output.appendLine(`Config open error: ${(err as Error).message}`);
  }
}

function buildResultFilePath(filePath: string): string {
  return `${filePath}.wlhresult`;
}

function buildPackageGroups(results: ScanResults, packages: string[]): PackageGroup[] {
  if (packages.length === 0) {
    return [];
  }
  const groups = packages.map((name) => ({
    name,
    crashes: [] as CrashEntry[]
  }));

  const matchesPackage = (text: string, packageName: string) =>
    text.toLowerCase().includes(packageName.toLowerCase());

  results.crashes.forEach((crash) => {
    groups.forEach((group) => {
      if (matchesPackage(crash.preview, group.name)) {
        group.crashes.push(crash);
      }
    });
  });

  return groups;
}

async function waitForResultFile(filePath: string, timeoutMs: number): Promise<string> {
  const target = buildResultFilePath(filePath);
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (fs.existsSync(target)) {
      const content = fs.readFileSync(target, 'utf8');
      if (content.trim().length > 0) {
        return content;
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error('result_file_timeout');
}

async function scanFull(filePath: string) {
  output.appendLine(`Scanning ${filePath}`);
  sidebarProvider?.update('Scanning...');
  sidebarProvider?.updateStatus('Scanning...');
  sidebarProvider?.updateFilePath(filePath);
  const engineConfig = readEngineConfig();
  if (engineConfig.scanPackages.length === 0) {
    sidebarProvider?.update('No packages configured.');
    sidebarProvider?.updateStatus('Error');
    vscode.window.showErrorMessage('WLH: Configure scanPackages in engine config.');
    return;
  }
  try {
    const scanResult = await runWlhJson<{ status: string; jobId?: string }>([
      'scan',
      filePath
    ]);
    if (scanResult.status !== 'ok') {
      sidebarProvider?.update(JSON.stringify(scanResult));
      return;
    }

    const raw = await waitForResultFile(filePath, 120_000);
    const results = normalizeResults(JSON.parse(raw));
    const grouped = buildPackageGroups(results, engineConfig.scanPackages);
    sidebarProvider?.updateResults(results, grouped, engineConfig.scanPackages);
    sidebarProvider?.updateStatus('Scan complete');
    output.appendLine(`Scan complete: ${filePath}`);
  } catch (err) {
    output.appendLine(`WLH error: ${(err as Error).message}`);
    sidebarProvider?.update(`Error: ${(err as Error).message}`);
    sidebarProvider?.updateStatus('Error');
  }
}

async function decryptFile(filePath: string) {
  const config = vscode.workspace.getConfiguration('wlh');
  const jarPath = config.get<string>('decrypt.jarPath') || '';
  const timeoutSeconds = Math.max(1, config.get<number>('decrypt.timeoutSeconds') || 600);
  if (!jarPath.trim()) {
    vscode.window.showErrorMessage('WLH: Set wlh.decrypt.jarPath before decrypt.');
    return;
  }
  try {
    sidebarProvider?.update('Decrypting...');
    sidebarProvider?.updateStatus('Decrypting...');
    const result = await runWlhJson<Record<string, unknown>>([
      'decrypt',
      filePath,
      '--jar',
      jarPath,
      '--timeout',
      String(timeoutSeconds)
    ]);
    const status = String(result.status || '');
    const outputPath = String(result.output || '');
    output.appendLine(JSON.stringify(result));
    if (status === 'ok') {
      sidebarProvider?.update(JSON.stringify(result));
      sidebarProvider?.updateStatus('Decrypt complete');
      if (outputPath) {
        try {
          const doc = await vscode.workspace.openTextDocument(outputPath);
          await vscode.window.showTextDocument(doc, { preview: false });
        } catch (err) {
          output.appendLine(`Decrypt open error: ${(err as Error).message}`);
        }
      }
    } else {
      const message = String(result.message || 'decrypt_failed');
      vscode.window.showErrorMessage(`WLH: Decrypt failed: ${message}`);
      sidebarProvider?.update(JSON.stringify(result));
      sidebarProvider?.updateStatus('Error');
    }
  } catch (err) {
    const message = (err as Error).message;
    vscode.window.showErrorMessage(`WLH: Decrypt failed: ${message}`);
    sidebarProvider?.update(`Error: ${message}`);
    sidebarProvider?.updateStatus('Error');
  }
}

function resolveActiveFilePath(): string | undefined {
  const editor = vscode.window.activeTextEditor;
  if (editor) {
    return editor.document.fileName;
  }

  const tab = vscode.window.tabGroups.activeTabGroup?.activeTab;
  if (!tab) {
    return undefined;
  }

  const input = tab.input;
  if (input instanceof vscode.TabInputText) {
    return input.uri.fsPath;
  }
  if (input instanceof vscode.TabInputTextDiff) {
    return input.modified.fsPath;
  }
  return undefined;
}

let sidebarProvider: WlhSidebarProvider | undefined;

function resolveDefaultHome(): string {
  return path.join(os.homedir(), '.wlh');
}

function resolveEngineJarPath(): string {
  return path.join(resolveDefaultHome(), 'engine', 'wlh-engine.jar');
}

async function runEngineDirect() {
  ensureHomeDirectory();
  const jarPath = resolveEngineJarPath();
  const homePath = resolveDefaultHome();
  const extraArgs =
    (await vscode.window.showInputBox({
      title: 'WLH Run Engine Direct',
      prompt: 'Optional extra args (e.g., --port 0)',
      placeHolder: '--port 0'
    })) || '';
  const terminal = vscode.window.createTerminal('WLH Engine');
  const command = `java -jar "${jarPath}" --home "${homePath}" ${extraArgs}`.trim();
  terminal.show();
  terminal.sendText(command);
}

async function runWlhCommand(args: string[], label: string) {
  sidebarProvider?.updateStatus(label);
  try {
    const result = await runWlhJson<Record<string, unknown>>(args);
    sidebarProvider?.updateStatus(`${label}: ok`);
    output.appendLine(JSON.stringify(result));
  } catch (err) {
    const message = (err as Error).message;
    sidebarProvider?.updateStatus(`${label}: error`);
    vscode.window.showErrorMessage(`WLH: ${label} failed: ${message}`);
  }
}

export function activate(context: vscode.ExtensionContext) {
  output.appendLine('WLH extension activated');
  sidebarProvider = new WlhSidebarProvider(
    async (filePath, line) => {
      try {
        const doc = await vscode.workspace.openTextDocument(filePath);
        const editor = await vscode.window.showTextDocument(doc, { preview: false });
        const targetLine = Math.max(0, line - 1);
        const position = new vscode.Position(targetLine, 0);
        editor.selection = new vscode.Selection(position, position);
        editor.revealRange(
          new vscode.Range(position, position),
          vscode.TextEditorRevealType.InCenter
        );
      } catch (err) {
        vscode.window.showErrorMessage(`WLH: Failed to open ${filePath}`);
        output.appendLine(`Jump error: ${(err as Error).message}`);
      }
    },
    async (filePath) => {
      await decryptFile(filePath);
    },
    async () => {
      await vscode.commands.executeCommand('workbench.action.openSettings', 'wlh');
    },
    async () => {
      await runWlhCommand(['status', '--no-update'], 'Status');
    },
    async () => {
      await runWlhCommand(['start'], 'Start');
    },
    async () => {
      await runWlhCommand(['restart'], 'Restart');
    },
    async () => {
      await runWlhCommand(['stop'], 'Stop');
    },
    async () => {
      await runEngineDirect();
    },
    async () => {
      ensureHomeDirectory();
      const homePath = resolveDefaultHome();
      await vscode.commands.executeCommand('revealFileInOS', vscode.Uri.file(homePath));
    },
    async (filePath) => {
      await scanFull(filePath);
    }
  );
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider('wlh.sidebar', sidebarProvider)
  );

  const config = vscode.workspace.getConfiguration('wlh');
  const commandPath = config.get<string>('commandPath') || '';
  const decryptJarPath = config.get<string>('decrypt.jarPath') || '';
  if (!commandPath.trim()) {
    vscode.window.showErrorMessage('WLH: Set wlh.commandPath (bootstrap path) before use.');
  }
  if (!decryptJarPath.trim()) {
    vscode.window.showErrorMessage('WLH: Set wlh.decrypt.jarPath before use.');
  }
  const scanCommand = vscode.commands.registerCommand('wlh.scan', async () => {
    const filePath = resolveActiveFilePath();
    if (!filePath) {
      vscode.window.showInformationMessage('WLH: No active file');
      return;
    }
    await scanFull(filePath);
  });
  const decryptCommand = vscode.commands.registerCommand('wlh.decryptCurrentFile', async () => {
    const filePath = resolveActiveFilePath();
    if (!filePath) {
      vscode.window.showInformationMessage('WLH: No active file');
      return;
    }
    await decryptFile(filePath);
  });
  const openSettingsCommand = vscode.commands.registerCommand('wlh.openSettings', async () => {
    await vscode.commands.executeCommand('workbench.action.openSettings', 'wlh');
  });
  const statusCommand = vscode.commands.registerCommand('wlh.status', async () => {
    await runWlhCommand(['status', '--no-update'], 'Status');
  });
  const startCommand = vscode.commands.registerCommand('wlh.start', async () => {
    await runWlhCommand(['start'], 'Start');
  });
  const restartCommand = vscode.commands.registerCommand('wlh.restart', async () => {
    await runWlhCommand(['restart'], 'Restart');
  });
  const stopCommand = vscode.commands.registerCommand('wlh.stop', async () => {
    await runWlhCommand(['stop'], 'Stop');
  });
  const runEngineCommand = vscode.commands.registerCommand('wlh.runEngineDirect', async () => {
    await runEngineDirect();
  });
  const openHomeCommand = vscode.commands.registerCommand('wlh.openHome', async () => {
    ensureHomeDirectory();
    const homePath = resolveDefaultHome();
    await vscode.commands.executeCommand('revealFileInOS', vscode.Uri.file(homePath));
  });

  const openListener = vscode.workspace.onDidOpenTextDocument(async (doc) => {
    if (doc.isUntitled) {
      return;
    }
    const lower = doc.fileName.toLowerCase();
    if (lower.endsWith('.log') || lower.endsWith('.txt')) {
      await scanFull(doc.fileName);
    }
  });
  const activeListener = vscode.window.onDidChangeActiveTextEditor((editor) => {
    if (!editor) {
      return;
    }
    sidebarProvider?.updateFilePath(editor.document.fileName);
  });

  context.subscriptions.push(
    scanCommand,
    decryptCommand,
    openSettingsCommand,
    statusCommand,
    startCommand,
    restartCommand,
    stopCommand,
    runEngineCommand,
    openHomeCommand,
    openListener,
    activeListener,
    output
  );
}

export function deactivate() {
  output.dispose();
}
