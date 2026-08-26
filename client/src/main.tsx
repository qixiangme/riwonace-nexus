import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Bot, Check, ChevronRight, Clock3, Database, FileText, Network, Plus, Send, Server, Table2, User, Wifi, WifiOff } from "lucide-react";
import "./styles.css";

type Route = "SQL" | "GRAPH" | "VECTOR";
type AgentAnswer = {
  answer: string;
  routes: Route[];
  toolCalls: string[];
  contextSources: string[];
  latencyMs?: number;
  selectedModel?: string;
  claimCoverage?: number;
  wasEscalated?: boolean;
};
type Message = { id: number; question: string; response?: AgentAnswer; error?: string; pending?: boolean };
type ToolState = { online: boolean; tools: string[]; resources: string[] };

const examples = [
  { route: "SQL" as const, label: "데이터 조회", question: "기술지원팀 인원수 알려줘", icon: Table2 },
  { route: "GRAPH" as const, label: "관계 검색", question: "경영지원팀 책임자 알려줘", icon: Network },
  { route: "VECTOR" as const, label: "문서 검색", question: "Client-F에게 Product-C1 도입을 제안하면서 제시한 초기 구축비는 얼마야?", icon: FileText },
];
const routeLabels: Record<Route, string> = { SQL: "데이터 조회", GRAPH: "관계 검색", VECTOR: "문서 검색" };

function cleanAnswer(answer: string) {
  return answer.split(/\n\s*\n출처:/)[0].trim();
}

function App() {
  const [question, setQuestion] = useState("");
  const [messages, setMessages] = useState<Message[]>([]);
  const [activeId, setActiveId] = useState<number | null>(null);
  const [tools, setTools] = useState<ToolState>({ online: false, tools: [], resources: [] });
  const [checking, setChecking] = useState(true);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const active = messages.find((message) => message.id === activeId) ?? messages[messages.length - 1];
  const busy = messages.some((message) => message.pending);

  useEffect(() => { void checkConnection(); }, []);

  async function checkConnection() {
    setChecking(true);
    try {
      const response = await fetch("/api/tools");
      if (!response.ok) throw new Error();
      const data = await response.json();
      setTools({ online: true, tools: data.mcpTools ?? [], resources: data.mcpResources ?? [] });
    } catch {
      setTools({ online: false, tools: [], resources: [] });
    } finally {
      setChecking(false);
    }
  }

  async function submit(nextQuestion = question) {
    const value = nextQuestion.trim();
    if (!value || busy) return;
    const id = Date.now();
    setQuestion("");
    setActiveId(id);
    setMessages((current) => [...current, { id, question: value, pending: true }]);
    try {
      const response = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ question: value }),
      });
      if (!response.ok) throw new Error(`서버가 HTTP ${response.status}를 반환했습니다.`);
      const data: AgentAnswer = await response.json();
      setMessages((current) => current.map((item) => item.id === id ? { ...item, pending: false, response: { ...data, answer: cleanAnswer(data.answer) } } : item));
    } catch (error) {
      setMessages((current) => current.map((item) => item.id === id ? { ...item, pending: false, error: error instanceof Error ? error.message : "요청에 실패했습니다." } : item));
    }
  }

  function reset() {
    setMessages([]);
    setActiveId(null);
    setQuestion("");
    textareaRef.current?.focus();
  }

  return <div className="app">
    <header className="header">
      <div className="logo"><span className="logo-symbol">VA</span><span>리원에이스</span></div>
      <div className="product"><i /> NEXUS <span>AI DATA SEARCH</span></div>
      <button className={`connection ${tools.online ? "online" : "offline"}`} onClick={checkConnection}>
        {tools.online ? <Wifi /> : <WifiOff />}
        <span>{checking ? "연결 확인 중" : tools.online ? "MCP 연결됨" : "서버 연결 안 됨"}</span>
      </button>
    </header>

    <div className="workspace">
      <aside className="sidebar">
        <button className="new-chat" onClick={reset}><Plus /> 새 질문</button>
        <section>
          <label>빠른 질문</label>
          {examples.map(({ route, label, question: sample, icon: Icon }) =>
            <button className="example" key={route} onClick={() => void submit(sample)} disabled={busy}>
              <span className={`route-icon ${route.toLowerCase()}`}><Icon /></span>
              <span><b>{label}</b><small>{sample}</small></span><ChevronRight />
            </button>
          )}
        </section>
        <section className="history">
          <label>질문 기록</label>
          {messages.length === 0 && <p>아직 실행한 질문이 없습니다.</p>}
          {[...messages].reverse().map((message) =>
            <button className={active?.id === message.id ? "active" : ""} key={message.id} onClick={() => setActiveId(message.id)}>
              <span>{message.question}</span><small>{message.pending ? "처리 중" : message.error ? "오류" : message.response?.routes.join(" + ")}</small>
            </button>
          )}
        </section>
        <div className="server-card">
          <div><Server /><span><b>Agent App</b><small>localhost:8080</small></span><i className={tools.online ? "on" : ""} /></div>
          <div><Database /><span><b>MCP Tools</b><small>{tools.tools.length ? `${tools.tools.length}개 사용 가능` : "확인되지 않음"}</small></span><i className={tools.tools.length ? "on" : ""} /></div>
        </div>
      </aside>

      <main className="chat">
        <div className="chat-scroll">
          {messages.length === 0 ? <div className="welcome">
            <span className="eyebrow">LIWONACE NEXUS</span>
            <h1>회사 데이터에<br />무엇이든 물어보세요.</h1>
            <p>데이터 조회, 관계 검색, 문서 검색을 한 화면에서 실행합니다.<br />모든 답변에는 사용한 도구와 근거가 함께 표시됩니다.</p>
            <div className="welcome-grid">
              {examples.map(({ route, label, question: sample, icon: Icon }) =>
                <button key={route} onClick={() => void submit(sample)} disabled={busy}><Icon /><span><b>{label}</b><small>{sample}</small></span><ChevronRight /></button>
              )}
            </div>
          </div> : messages.map((message) => <Conversation key={message.id} message={message} />)}
        </div>
        <form className="composer" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
          <textarea ref={textareaRef} value={question} onChange={(event) => setQuestion(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); void submit(); } }} placeholder="회사 데이터에 대해 질문하세요" rows={1} />
          <button type="submit" disabled={!question.trim() || busy}><Send /></button>
          <small>Enter로 전송 · Shift + Enter로 줄바꿈</small>
        </form>
      </main>

      <aside className="inspector">
        <div className="inspector-title"><span>실행 정보</span>{active?.response && <b>TRACE COMPLETE</b>}</div>
        {!active?.response ? <div className="inspector-empty"><Network /><p>질문을 실행하면<br />라우팅과 근거가 표시됩니다.</p></div> : <ResultInspector response={active.response} />}
      </aside>
    </div>
  </div>;
}

function Conversation({ message }: { message: Message }) {
  return <article className="conversation">
    <div className="bubble user"><span><User /></span><div><label>YOU</label><p>{message.question}</p></div></div>
    <div className="bubble assistant"><span><Bot /></span><div><label>NEXUS</label>
      {message.pending ? <div className="thinking"><i /><i /><i /><em>근거를 검색하고 있습니다</em></div> :
        message.error ? <p className="error">{message.error}</p> :
        <><p className="answer">{message.response?.answer}</p><div className="inline-meta">{message.response?.routes.map((route) => <b className={route.toLowerCase()} key={route}>{routeLabels[route]}</b>)}<span><Clock3 /> {message.response?.latencyMs?.toLocaleString() ?? "-"}ms</span></div></>}
    </div></div>
  </article>;
}

function ResultInspector({ response }: { response: AgentAnswer }) {
  return <div className="result-inspector">
    <section><label>선택된 경로</label><div className="route-stack">{response.routes.map((route) => <div key={route} className={route.toLowerCase()}><Check /><span><b>{route}</b><small>{routeLabels[route]}</small></span></div>)}</div></section>
    <section><label>호출한 MCP 도구</label><div className="tags">{response.toolCalls.map((tool) => <code key={tool}>{tool}</code>)}</div></section>
    <section><label>답변 근거</label><div className="sources">{response.contextSources.map((source) => <div key={source}><FileText /><span>{source}</span><Check /></div>)}</div></section>
    <section className="details"><label>실행 상세</label><dl><div><dt>모델</dt><dd>{response.selectedModel ?? "local model"}</dd></div><div><dt>지연 시간</dt><dd>{response.latencyMs?.toLocaleString() ?? "-"}ms</dd></div><div><dt>근거 충족도</dt><dd>{response.claimCoverage != null ? `${Math.round(response.claimCoverage * 100)}%` : "-"}</dd></div><div><dt>에스컬레이션</dt><dd>{response.wasEscalated ? "사용" : "미사용"}</dd></div></dl></section>
  </div>;
}

createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>);
