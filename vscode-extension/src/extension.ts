import * as vscode from 'vscode';
import { execFile } from 'child_process';
import * as os from 'os';
import * as path from 'path';

const output = vscode.window.createOutputChannel('WLH');

type CrashEntry = {
  line: number;
  preview: string;
};

type TagEntry = {
  tag: string;
  count: number;
  examples: string[];
};

type JsonBlockEntry = {
  id: number;
  startLine: number;
  endLine: number;
  preview: string;
  content: string;
};

type ScanResults = {
  filePath: string;
  versions: string[];
  crashes: CrashEntry[];
  tags: TagEntry[];
  jsonBlocks: JsonBlockEntry[];
};

class WlhSidebarProvider implements vscode.WebviewViewProvider {
  private view: vscode.WebviewView | undefined;
  private lastContent = 'No scans yet.';
  private lastStatus = 'Idle';
  private currentResults: ScanResults | undefined;

  constructor(
    private readonly onJump: (filePath: string, line: number) => void,
    private readonly onDecrypt: (filePath: string) => void,
    private readonly onOpenSettings: () => void,
    private readonly onStatus: () => void,
    private readonly onStart: () => void,
    private readonly onRestart: () => void,
    private readonly onStop: () => void,
    private readonly onRunEngineDirect: () => void,
    private readonly onOpenHome: () => void
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

  updateResults(results: ScanResults) {
    this.currentResults = results;
    this.lastContent = '';
    this.render();
  }

  private render() {
    if (!this.view) {
      return;
    }
    if (this.currentResults) {
      this.view.webview.html = this.renderResults(this.currentResults);
      return;
    }

    const safe = this.lastContent
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
    this.view.webview.html = `<!DOCTYPE html>
      <html>
        <body>
          <pre>${safe}</pre>
        </body>
      </html>`;
  }

  private renderResults(results: ScanResults): string {
    const escape = (value: string) =>
      value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const versions = results.versions.length
      ? results.versions.map((v) => `<li>${escape(v)}</li>`).join('')
      : '<li>None</li>';
    const crashes = results.crashes.length
      ? results.crashes
          .map(
            (c) =>
              `<li><button data-line="${c.line}">L${c.line}</button> ${escape(c.preview)}</li>`
          )
          .join('')
      : '<li>None</li>';
    const tags = results.tags.length
      ? results.tags
          .map((t) => `<li>${escape(t.tag)} (${t.count})</li>`)
          .join('')
      : '<li>None</li>';
    const blocks = results.jsonBlocks.length
      ? results.jsonBlocks
          .map(
            (b) =>
              `<li><button data-line="${b.startLine}">L${b.startLine}</button> ${escape(
                b.preview
              )}</li>`
          )
          .join('')
      : '<li>None</li>';

    return `<!DOCTYPE html>
      <html>
        <body>
          <h3>WLH Results</h3>
          <p>${escape(results.filePath)}</p>
          <div>
            <button id="status">Status</button>
            <button id="start">Start</button>
            <button id="restart">Restart</button>
            <button id="stop">Stop</button>
            <button id="runEngineDirect">Run Engine Direct</button>
            <button id="openHome">Open WLH Home</button>
            <button id="decrypt">Run Decrypt</button>
            <button id="openSettings">Open Settings</button>
          </div>
          <p><strong>Status:</strong> ${escape(this.lastStatus)}</p>
          <h4>Versions</h4>
          <ul>${versions}</ul>
          <h4>Crashes</h4>
          <ul>${crashes}</ul>
          <h4>Tags</h4>
          <ul>${tags}</ul>
          <h4>JSON Blocks</h4>
          <ul>${blocks}</ul>
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
            document.getElementById('decrypt').addEventListener('click', () => {
              vscode.postMessage({ type: 'decrypt' });
            });
            document.getElementById('openSettings').addEventListener('click', () => {
              vscode.postMessage({ type: 'openSettings' });
            });
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

function runWlh(args: string[]): Promise<string> {
  const config = vscode.workspace.getConfiguration('wlh');
  const baseUrl = config.get<string>('update.baseUrl');
  const commandPath = config.get<string>('commandPath') || 'wlh';
  if (!commandPath || commandPath.trim().length === 0) {
    vscode.window.showErrorMessage('WLH: Configure wlh.commandPath to the bootstrap script.');
    return Promise.reject(new Error('wlh_command_missing'));
  }
  const finalArgs = [...args];
  if (baseUrl && baseUrl.length > 0) {
    finalArgs.unshift(baseUrl);
    finalArgs.unshift('--base-url');
  }

  return new Promise((resolve, reject) => {
    execFile(commandPath, finalArgs, { maxBuffer: 10 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (stderr) {
        output.appendLine(stderr.trim());
      }
      if (err) {
        reject(err);
        return;
      }
      resolve(stdout.trim());
    });
  });
}

async function runWlhJson<T>(args: string[]): Promise<T> {
  const raw = await runWlh(args);
  return JSON.parse(raw) as T;
}

async function scanFastThenFull(filePath: string) {
  output.appendLine(`Scanning ${filePath}`);
  sidebarProvider?.update('Scanning...');
  sidebarProvider?.updateStatus('Scanning...');
  try {
    const scanResult = await runWlhJson<{ status: string; jobId?: string }>([
      'scan',
      '--mode',
      'fast_then_full',
      filePath
    ]);
    if (scanResult.status !== 'ok') {
      sidebarProvider?.update(JSON.stringify(scanResult));
      return;
    }

    const versions = await runWlhJson<{ status: string; versions?: string[] }>([
      'versions',
      filePath
    ]);
    const crashes = await runWlhJson<{ status: string; crashes?: CrashEntry[] }>([
      'crashes',
      filePath
    ]);
    const tags = await runWlhJson<{ status: string; tags?: TagEntry[] }>([
      'tags',
      filePath,
      '--limit',
      '100'
    ]);
    const jsonBlocks = await runWlhJson<{ status: string; jsonBlocks?: JsonBlockEntry[] }>([
      'json-blocks',
      filePath,
      '--limit',
      '50'
    ]);

    const results: ScanResults = {
      filePath,
      versions: versions.versions || [],
      crashes: crashes.crashes || [],
      tags: tags.tags || [],
      jsonBlocks: jsonBlocks.jsonBlocks || []
    };
    sidebarProvider?.updateResults(results);
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
      jarPath
    ]);
    sidebarProvider?.update(JSON.stringify(result));
    sidebarProvider?.updateStatus('Decrypt complete');
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
      const homePath = resolveDefaultHome();
      await vscode.commands.executeCommand('revealFileInOS', vscode.Uri.file(homePath));
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
  const scanCommand = vscode.commands.registerCommand('wlh.scanFastThenFull', async () => {
    const filePath = resolveActiveFilePath();
    if (!filePath) {
      vscode.window.showInformationMessage('WLH: No active file');
      return;
    }
    await scanFastThenFull(filePath);
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
    const homePath = resolveDefaultHome();
    await vscode.commands.executeCommand('revealFileInOS', vscode.Uri.file(homePath));
  });

  const openListener = vscode.workspace.onDidOpenTextDocument(async (doc) => {
    if (doc.isUntitled) {
      return;
    }
    const lower = doc.fileName.toLowerCase();
    if (lower.endsWith('.log') || lower.endsWith('.txt')) {
      await scanFastThenFull(doc.fileName);
    }
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
    output
  );
}

export function deactivate() {
  output.dispose();
}
