'use client';

import React, { useState, useEffect, useRef } from 'react';
import ReactMarkdown from 'react-markdown';
import { useChat } from 'ai/react';

type Session = {
  id: string;
  title: string;
  username: string;
  accountType: string;
  messages: any[];
};

export default function Home() {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);

  const [username, setUsername] = useState('');
  const [accountType, setAccountType] = useState('normal');

  const messagesEndRef = useRef<HTMLDivElement>(null);

  const { messages, input, handleInputChange, handleSubmit, setMessages, isLoading } = useChat({
    api: '/api/chat',
    body: {
      username,
      accountType,
      sessionId: currentSessionId,
    },
    onFinish: () => {
      fetchSessions(); // Refresh sessions to get new title if needed
    }
  });

  // Fetch past sessions
  const fetchSessions = async () => {
    try {
      const res = await fetch('/api/sessions');
      if (res.ok) {
        const data = await res.json();
        setSessions(data);
      }
    } catch (e) {
      console.error(e);
    }
  };

  useEffect(() => {
    fetchSessions();
  }, []);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const startNewSession = () => {
    setCurrentSessionId(null);
    setMessages([]);
  };

  const deleteSession = async (e: React.MouseEvent, id: string) => {
    e.stopPropagation();

    if (window.confirm('Are you sure you want to delete this session? This action cannot be undone.')) {
      try {
        const res = await fetch(`/api/sessions/${id}`, {
          method: 'DELETE',
        });

        if (res.ok) {
          fetchSessions();
          if (currentSessionId === id) {
            startNewSession();
          }
        }
      } catch (err) {
        console.error('Failed to delete session:', err);
      }
    }
  };

  const loadSession = (session: Session) => {
    setCurrentSessionId(session.id);
    setUsername(session.username);
    setAccountType(session.accountType);
    setMessages(session.messages.map(m => ({
      id: m.id,
      role: m.role as 'user' | 'assistant',
      content: m.content
    })));
  };

  return (
    <>
      <aside className="glass-panel" style={{ width: '320px', borderRight: '1px solid var(--border-light)', display: 'flex', flexDirection: 'column', height: '100%', borderRadius: 0 }}>
        <div style={{ padding: '1.5rem', borderBottom: '1px solid var(--border-light)' }}>
          <h1 className="osrs-title" style={{ fontSize: '1.25rem', marginBottom: '0.5rem' }}>OSRS AI Assistant</h1>
          <p style={{ fontSize: '0.875rem', color: 'var(--text-muted)' }}>Log in or query using your stats.</p>
        </div>

        <div style={{ flex: 1, padding: '1rem', overflowY: 'auto' }}>
          <div style={{ marginBottom: '1rem' }}>
            <h2 style={{ fontSize: '0.75rem', textTransform: 'uppercase', color: 'var(--text-gold)', letterSpacing: '1px', marginBottom: '0.5rem' }}>Account Settings</h2>

            <input
              type="text"
              placeholder="OSRS Username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              style={{ width: '100%', marginBottom: '0.5rem' }}
            />
            <select
              value={accountType}
              onChange={(e) => setAccountType(e.target.value)}
              style={{ width: '100%' }}
            >
              <option value="normal">Normal Account</option>
              <option value="ironman">Ironman (IM)</option>
              <option value="hardcore">Hardcore Ironman (HCIM)</option>
              <option value="ultimate">Ultimate Ironman (UIM)</option>
            </select>
          </div>

          <button className="btn-primary" onClick={startNewSession} style={{ width: '100%', marginTop: '0.5rem' }}>
            New Session
          </button>

          <div style={{ marginTop: '2rem' }}>
            <h2 style={{ fontSize: '0.75rem', textTransform: 'uppercase', color: 'var(--text-gold)', letterSpacing: '1px', marginBottom: '0.5rem' }}>Recent Chat Sessions</h2>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', marginTop: '1rem' }}>
              {sessions.length === 0 ? (
                <div style={{ color: 'var(--text-muted)', fontSize: '0.875rem', textAlign: 'center' }}>No recent sessions.</div>
              ) : (
                sessions.map(s => (
                  <div
                    key={s.id}
                    onClick={() => loadSession(s)}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      textAlign: 'left',
                      padding: '0.75rem',
                      background: currentSessionId === s.id ? 'var(--bg-panel-hover)' : 'transparent',
                      borderRadius: 'var(--radius-md)',
                      fontSize: '0.875rem',
                      color: currentSessionId === s.id ? 'var(--text-main)' : 'var(--text-muted)',
                      border: currentSessionId === s.id ? '1px solid var(--border-light)' : '1px solid transparent',
                      cursor: 'pointer',
                      transition: 'all 0.2s ease',
                      position: 'relative',
                    }}
                    className="session-item"
                  >
                    <div style={{ flex: 1, minWidth: 0, paddingRight: '0.5rem' }}>
                      <div style={{ fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.title || 'New Chat'}</div>
                      <div style={{ fontSize: '0.75rem', opacity: 0.7, marginTop: '0.25rem' }}>{s.username} ({s.accountType})</div>
                    </div>
                    <button
                      onClick={(e) => deleteSession(e, s.id)}
                      style={{
                        background: 'transparent',
                        border: 'none',
                        color: 'var(--text-muted)',
                        cursor: 'pointer',
                        padding: '0.25rem',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        borderRadius: 'var(--radius-sm)',
                      }}
                      title="Delete Session"
                      className="delete-session-btn"
                    >
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                        <line x1="10" y1="11" x2="10" y2="17"></line>
                        <line x1="14" y1="11" x2="14" y2="17"></line>
                      </svg>
                    </button>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </aside>

      <main style={{ flex: 1, display: 'flex', flexDirection: 'column', position: 'relative' }}>
        <div style={{ flex: 1, padding: '2rem', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '1rem' }}>

          {messages.length === 0 ? (
            <div style={{ alignSelf: 'center', marginTop: 'auto', marginBottom: 'auto', textAlign: 'center', maxWidth: '600px' }}>
              <h2 className="osrs-title" style={{ fontSize: '2rem', marginBottom: '1rem' }}>Welcome Adventurer</h2>
              <p style={{ color: 'var(--text-muted)' }}>
                Enter your OSRS username on the left, select your account type, and ask me any question about your journey ahead. I will verify your skills and offer advice suited specifically to your restrictions.
              </p>
            </div>
          ) : (
            messages.map((m) => (
              <div key={m.id} style={{
                alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start',
                maxWidth: '75%',
                padding: '1rem',
                borderRadius: 'var(--radius-lg)',
                backgroundColor: m.role === 'user' ? 'var(--bg-panel-hover)' : 'var(--bg-panel)',
                border: `1px solid ${m.role === 'user' ? 'var(--border-light)' : 'var(--border-primary)'}`,
                borderBottomRightRadius: m.role === 'user' ? '4px' : 'var(--radius-lg)',
                borderBottomLeftRadius: m.role === 'assistant' ? '4px' : 'var(--radius-lg)',
                boxShadow: 'var(--shadow-sm)'
              }}>
                <div style={{ fontSize: '0.75rem', color: m.role === 'user' ? 'var(--text-muted)' : 'var(--text-gold)', marginBottom: '0.75rem', fontWeight: 600 }}>
                  {m.role === 'user' ? (username ? username : 'Guest') : 'Wise Old Man (AI)'}
                </div>
                <div style={{ lineHeight: 1.6 }} className={m.role === 'user' ? 'user-markdown' : 'ai-markdown'}>
                  <ReactMarkdown>{m.content}</ReactMarkdown>
                </div>
              </div>
            ))
          )}
          {isLoading && (
            <div style={{ alignSelf: 'flex-start', color: 'var(--text-muted)', fontSize: '0.875rem', padding: '1rem' }}>
              Consulting the ancient tomes...
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>

        <div style={{ padding: '1.5rem', borderTop: '1px solid var(--border-light)', backgroundColor: 'var(--bg-dark)' }}>
          <form onSubmit={handleSubmit} style={{ display: 'flex', gap: '1rem', maxWidth: '800px', margin: '0 auto' }}>
            <input
              type="text"
              value={input}
              onChange={handleInputChange}
              placeholder="Ask about leveling paths, specific items, or quest requirements..."
              style={{ flex: 1, padding: '1rem', borderRadius: 'var(--radius-lg)' }}
              disabled={isLoading}
            />
            <button className="btn-primary" type="submit" disabled={isLoading || !input || !input.trim()} style={{ borderRadius: 'var(--radius-lg)', padding: '0 2rem' }}>
              Send
            </button>
          </form>
          <div style={{ textAlign: 'center', marginTop: '0.75rem', fontSize: '0.75rem', color: 'var(--text-muted)' }}>
            Data fetched directly from the OSRS Official Hiscores.
          </div>
        </div>
      </main>
    </>
  );
}
