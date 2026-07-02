import React, { useRef, useState, useEffect, useCallback } from 'react';
  import {
    View, Text, StyleSheet, TouchableOpacity, PanResponder,
    Animated, ScrollView, ActivityIndicator, Dimensions, Alert,
  } from 'react-native';
  import {
    getDomains, createAccount, getToken,
    getMessages, getMessage, extractVerifyLink,
    MailMessage,
  } from '../utils/mailtm';

  const { width: SW, height: SH } = Dimensions.get('window');
  const WIN_W = SW - 24;
  const WIN_H = SH * 0.45;

  interface Props {
    onClose: () => void;
    onVerifyLink: (url: string) => void;
    /** If provided, auto-login with this email+password */
    autoEmail?: string;
    autoPassword?: string;
  }

  export default function FloatingMailWindow({ onClose, onVerifyLink, autoEmail, autoPassword }: Props) {
    const pan = useRef(new Animated.ValueXY({ x: 12, y: SH * 0.35 })).current;
    const panResponder = useRef(
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onPanResponderGrant: () => {
          pan.setOffset({ x: (pan.x as any)._value, y: (pan.y as any)._value });
          pan.setValue({ x: 0, y: 0 });
        },
        onPanResponderMove: Animated.event([null, { dx: pan.x, dy: pan.y }], { useNativeDriver: false }),
        onPanResponderRelease: () => pan.flattenOffset(),
      })
    ).current;

    const [email, setEmail] = useState(autoEmail || '');
    const [password, setPassword] = useState(autoPassword || '');
    const [token, setToken] = useState<string | null>(null);
    const [messages, setMessages] = useState<MailMessage[]>([]);
    const [loading, setLoading] = useState(false);
    const [status, setStatus] = useState('Nhập email mail.tm để theo dõi');
    const [polling, setPolling] = useState(false);
    const pollRef = useRef<any>(null);

    // Auto-login if autoEmail provided
    useEffect(() => {
      if (autoEmail && autoPassword) { handleLogin(autoEmail, autoPassword); }
      return () => { if (pollRef.current) clearInterval(pollRef.current); };
    }, []);

    const handleLogin = async (em?: string, pw?: string) => {
      const e = em || email; const p = pw || password;
      if (!e || !p) { Alert.alert('Nhập email và password'); return; }
      setLoading(true);
      setStatus('Đang lấy token...');
      const tok = await getToken(e, p);
      if (!tok) { setStatus('❌ Sai email/password'); setLoading(false); return; }
      setToken(tok);
      setStatus('✅ Đã kết nối — đang theo dõi email...');
      setLoading(false);
      startPolling(tok);
    };

    const startPolling = useCallback((tok: string) => {
      setPolling(true);
      const seen = new Set<string>();
      const poll = async () => {
        const msgs = await getMessages(tok);
        setMessages(msgs);
        for (const msg of msgs) {
          if (seen.has(msg.id)) continue;
          seen.add(msg.id);
          if (msg.subject.toLowerCase().includes('replit') ||
              msg.from.address.includes('replit')) {
            const detail = await getMessage(tok, msg.id);
            if (detail) {
              const link = extractVerifyLink(detail);
              if (link) {
                setStatus('🔗 Tìm thấy link verify! Đang mở...');
                onVerifyLink(link);
              }
            }
          }
        }
      };
      poll();
      pollRef.current = setInterval(poll, 3000);
    }, [onVerifyLink]);

    return (
      <Animated.View
        style={[styles.container, { transform: pan.getTranslateTransform() }]}>
        {/* Header — drag handle */}
        <View style={styles.header} {...panResponder.panHandlers}>
          <Text style={styles.title}>📧 Mail.tm Monitor</Text>
          <View style={styles.headerRight}>
            {polling && <ActivityIndicator size="small" color="#58a6ff" style={{ marginRight: 8 }} />}
            <TouchableOpacity onPress={onClose}>
              <Text style={styles.close}>✕</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Status */}
        <Text style={styles.status}>{status}</Text>

        {/* Message list */}
        <ScrollView style={styles.msgList}>
          {messages.length === 0 && (
            <Text style={styles.empty}>Chưa có email nào...</Text>
          )}
          {messages.map(m => (
            <View key={m.id} style={[styles.msgRow, !m.seen && styles.msgUnread]}>
              <Text style={styles.msgFrom} numberOfLines={1}>{m.from.address}</Text>
              <Text style={styles.msgSubject} numberOfLines={1}>{m.subject}</Text>
              <Text style={styles.msgIntro} numberOfLines={1}>{m.intro}</Text>
            </View>
          ))}
        </ScrollView>
      </Animated.View>
    );
  }

  const styles = StyleSheet.create({
    container: {
      position: 'absolute', width: WIN_W, height: WIN_H,
      backgroundColor: '#161b22', borderRadius: 12,
      borderWidth: 1, borderColor: '#30363d',
      shadowColor: '#000', shadowOpacity: 0.5,
      shadowRadius: 10, elevation: 20, overflow: 'hidden',
    },
    header: {
      flexDirection: 'row', alignItems: 'center',
      justifyContent: 'space-between',
      backgroundColor: '#0d1117', padding: 12,
      borderBottomWidth: 1, borderBottomColor: '#30363d',
    },
    title: { color: '#58a6ff', fontWeight: 'bold', fontSize: 14 },
    headerRight: { flexDirection: 'row', alignItems: 'center' },
    close: { color: '#6e7681', fontSize: 18, paddingHorizontal: 4 },
    status: {
      color: '#8b949e', fontSize: 11, paddingHorizontal: 12,
      paddingVertical: 6, backgroundColor: '#0d1117',
    },
    msgList: { flex: 1 },
    empty: { color: '#484f58', textAlign: 'center', marginTop: 24, fontSize: 13 },
    msgRow: {
      padding: 10, borderBottomWidth: 1, borderBottomColor: '#21262d',
    },
    msgUnread: { backgroundColor: '#1f2937' },
    msgFrom: { color: '#8b949e', fontSize: 11 },
    msgSubject: { color: '#c9d1d9', fontSize: 13, fontWeight: '600', marginTop: 2 },
    msgIntro: { color: '#6e7681', fontSize: 11, marginTop: 2 },
  });