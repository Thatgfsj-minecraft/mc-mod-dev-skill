/**
 * MCB v1 consumer — TCP NDJSON client with lazy connect, response matching
 * and self-healing reconnect.
 *
 * Protocol: references/debug-mcp/protocol.md
 *
 * Design (protocol.md §0.2): all cursor/reconnect/matching complexity lives
 * HERE so every bridge implementation (any loader, any version) can stay dumb.
 * This file deliberately knows nothing about Minecraft.
 */
import net from "node:net";

export const MCB_PORT = Number(process.env.MCB_PORT ?? 25585);
export const MCB_HOST = process.env.MCB_HOST ?? "127.0.0.1";

export interface Hello {
  protocol: number;
  game: string;
  loader: string;
  side: string;
  worldLoaded: boolean;
}

export interface BridgeResponse {
  ok: boolean;
  data?: any;
  error?: { code: string; message: string };
}

export class BridgeError extends Error {
  constructor(public code: string, message: string) {
    super(message);
  }
}

// ---------------------------------------------------------------------------
// Envelope parsing: one JSON object per line, three shapes (protocol §2).
// ---------------------------------------------------------------------------
type Pending = {
  resolve: (r: BridgeResponse) => void;
  reject: (e: Error) => void;
  timer: NodeJS.Timeout;
};

export class Bridge {
  private socket: net.Socket | null = null;
  private pending = new Map<number, Pending>();
  private nextId = 1;
  private buffer = "";
  private hello: Hello | null = null;
  private connecting: Promise<void> | null = null;

  get helloInfo(): Hello | null {
    return this.hello;
  }

  get connected(): boolean {
    return this.socket !== null && !this.socket.destroyed;
  }

  /**
   * Lazy connect: called before every request. Reuses the existing socket,
   * dedupes concurrent connect attempts, and re-connects transparently after
   * the game restarts (the old socket dies silently — that is expected).
   */
  async ensureConnected(): Promise<Hello> {
    if (this.connected && this.hello) return this.hello;
    if (this.connecting) {
      await this.connecting;
      if (this.hello) return this.hello;
    }
    this.connecting = this.open().finally(() => {
      this.connecting = null;
    });
    await this.connecting;
    return this.hello!;
  }

  private open(): Promise<void> {
    return new Promise((resolve, reject) => {
      const socket = net.connect(MCB_PORT, MCB_HOST, () => {
        /* hello arrives as first line */
      });
      socket.setEncoding("utf8");
      this.hello = null;

      const timeout = setTimeout(() => {
        socket.destroy();
        this.socket = null;
        reject(new BridgeError(
          "BRIDGE_UNREACHABLE",
          `no MCB bridge listening on ${MCB_HOST}:${MCB_PORT} — is the game running with the debug bridge mod installed?`
        ));
      }, 3000);

      socket.on("data", (chunk: string) => {
        this.buffer += chunk;
        let idx: number;
        while ((idx = this.buffer.indexOf("\n")) >= 0) {
          const line = this.buffer.slice(0, idx).trim();
          this.buffer = this.buffer.slice(idx + 1);
          if (line) this.handleLine(line, resolve, timeout);
        }
      });

      socket.on("error", (err: Error) => {
        clearTimeout(timeout);
        this.teardown();
        reject(new BridgeError("BRIDGE_UNREACHABLE", err.message));
      });

      socket.on("close", () => {
        this.teardown();
      });
    });
  }

  private handleLine(line: string, connectResolve?: () => void, connectTimeout?: NodeJS.Timeout) {
    let envelope: any;
    try {
      envelope = JSON.parse(line);
    } catch {
      return; // protocol violation from a broken bridge — drop the line
    }
    if (envelope.event === "hello") {
      this.hello = envelope.data as Hello;
      if (this.hello.protocol !== 1) {
        this.disconnect();
        throw new BridgeError(
          "PROTOCOL_MISMATCH",
          `bridge speaks MCB v${this.hello.protocol}, consumer speaks v1`
        );
      }
      if (connectTimeout) clearTimeout(connectTimeout);
      connectResolve?.();
      return;
    }
    if (typeof envelope.id === "number" && (envelope.ok !== undefined || envelope.error !== undefined)) {
      const p = this.pending.get(envelope.id);
      if (p) {
        this.pending.delete(envelope.id);
        clearTimeout(p.timer);
        p.resolve(envelope as BridgeResponse);
      }
      // Unknown id = response for a request that already timed out. Drop.
      return;
    }
    // `log` events are pushed but currently only v1 reference tools use
    // log.history / log.wait ops directly; event stream is kept for future
    // tools (live tail) — see protocol.md §2.3.
  }

  private teardown() {
    this.hello = null;
    const socket = this.socket;
    this.socket = null;
    for (const [, p] of this.pending) {
      clearTimeout(p.timer);
      p.reject(new BridgeError("BRIDGE_DISCONNECTED", "bridge connection closed"));
    }
    this.pending.clear();
    if (socket && !socket.destroyed) socket.destroy();
  }

  disconnect() {
    this.teardown();
  }

  /** Issue one op request and await its (unique) response. */
  async request(op: string, params: Record<string, any> = {}, timeoutMs = 30000): Promise<any> {
    await this.ensureConnected();
    const id = this.nextId++;
    const payload = JSON.stringify({ id, op, params }) + "\n";
    return new Promise<any>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new BridgeError("TIMEOUT", `op ${op} timed out after ${timeoutMs}ms`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.socket!.write(payload);
    }).then(async (resp: BridgeResponse) => {
      if (resp.ok) return resp.data;
      throw new BridgeError(resp.error!.code, resp.error!.message);
    });
  }
}

// Module-level singleton: one bridge per MCP process.
export const bridge = new Bridge();
