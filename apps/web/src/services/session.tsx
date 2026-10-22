import { createContext, useContext } from "react";
import type { User } from "./types";
export const SessionContext = createContext<{
  user: User;
  refresh: () => void;
}>({ user: null as any, refresh: () => {} });
export const useSession = () => useContext(SessionContext);
