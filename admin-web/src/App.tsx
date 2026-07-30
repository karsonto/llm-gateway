import { Navigate, Route, Routes } from "react-router-dom";
import { getToken } from "./api";
import LoginPage from "./pages/LoginPage";
import Layout from "./pages/Layout";
import KeysPage from "./pages/KeysPage";
import UsageByKeyPage from "./pages/UsageByKeyPage";
import UsageByGroupPage from "./pages/UsageByGroupPage";

function RequireAuth({ children }: { children: React.ReactNode }) {
  if (!getToken()) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/keys" replace />} />
        <Route path="keys" element={<KeysPage />} />
        <Route path="usage/key" element={<UsageByKeyPage />} />
        <Route path="usage/group" element={<UsageByGroupPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
