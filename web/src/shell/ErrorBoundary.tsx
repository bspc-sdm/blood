// কোনো অংশ ভেঙে গেলে (বা নতুন ডিপ্লয়ের পর পুরোনো ফাইল না পেলে) সাদা পাতার বদলে "আবার লোড করুন"
import { Component, type ReactNode } from 'react';
import { tr } from '@/lib/i18n';

export class ErrorBoundary extends Component<{ children: ReactNode; resetKey?: string }, { error: Error | null }> {
  state = { error: null as Error | null };
  static getDerivedStateFromError(error: Error) {
    return { error };
  }
  componentDidUpdate(prev: { resetKey?: string }) {
    // অন্য পাতায় গেলে আবার চেষ্টা
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.setState({ error: null });
  }
  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="empty" role="alert">
        <div className="t">{tr('Something went wrong', 'কিছু একটা সমস্যা হয়েছে')}</div>
        <div className="s">{tr('Reload the page to get the latest version.', 'নতুন সংস্করণ পেতে পাতাটা আবার লোড করুন।')}</div>
        <button type="button" className="btn" onClick={() => window.location.reload()}>{tr('Reload', 'আবার লোড করুন')}</button>
      </div>
    );
  }
}
