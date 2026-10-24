/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 비밀번호 설정 완료 후 이동할 Tetra Portal 로그인 URL */
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
